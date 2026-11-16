package dev.omnisync

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Base64
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("profile").orEmpty()
        val force = inputData.getBoolean("force", false)
        val why = inputData.getString("why") ?: "scheduled"
        val targets = if (id.isEmpty()) Store.profiles().filter { it.enabled } else listOfNotNull(Store.profile(id))
        targets.forEach { SyncEngine.sync(it, force, why) }
        Result.success()
    }
}

object Scheduler {
    private const val TAG = "periodic"

    private fun constraints(p: Profile?): Constraints {
        val needsNet = p == null || Store.account(p.accountId)?.type != Account.TYPE_LOCAL
        return Constraints.Builder().setRequiredNetworkType(if (needsNet) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).build()
    }

    /** (Re)creates one periodic job per enabled profile and cancels stale ones. */
    fun scheduleAll(ctx: Context) = thread {
        val wm = WorkManager.getInstance(ctx)
        val profiles = Store.profiles().filter { it.enabled }
        val wanted = profiles.map { "p-${it.id}" }.toSet()
        try {
            wm.getWorkInfosByTag(TAG).get().forEach { info ->
                val name = info.tags.firstOrNull { it.startsWith("p-") }
                if (name != null && name !in wanted) wm.cancelUniqueWork(name)
            }
        } catch (e: Exception) { Log.e("schedule cleanup", e) }
        profiles.forEach { p ->
            val req = PeriodicWorkRequestBuilder<SyncWorker>(maxOf(15, p.intervalMin).toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints(p))
                .addTag(TAG).addTag("p-${p.id}")
                .setInputData(workDataOf("profile" to p.id, "why" to "periodic"))
                .build()
            wm.enqueueUniquePeriodicWork("p-${p.id}", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    fun oneShot(ctx: Context, profileId: String?, delaySec: Long = 0, why: String = "manual", force: Boolean = false) {
        val p = profileId?.let { Store.profile(it) }
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setConstraints(constraints(p))
            .setInputData(workDataOf("profile" to (p?.id ?: ""), "why" to why, "force" to force))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("once-${p?.id ?: "all"}", ExistingWorkPolicy.REPLACE, req)
    }
}

/**
 * Root-mode helper: knows which app is in front. When a watched app (e.g. Samsung Notes) goes to
 * the background we push its changes; when it comes to the front and the cloud has something newer,
 * we stop it, pull, and reopen it — so you always edit the latest copy.
 */
class AppWatcherService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val ignore = setOf("com.android.systemui", "android", "dev.omnisync",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.intentresolver", "com.miui.securitycenter")

    override fun onServiceConnected() { Log.i("App watcher connected") }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        if (pkg in ignore || pkg == ime) return
        val prev = foregroundPkg
        if (pkg == prev) return
        foregroundPkg = pkg
        val watched = Store.profiles().filter { it.enabled && it.watchApp && it.pkg.isNotBlank() }
        watched.filter { it.pkg == prev }.forEach {
            Log.i("[${it.name}] app left foreground → push soon")
            Scheduler.oneShot(this, it.id, 5, "app closed")
        }
        watched.filter { it.pkg == pkg }.forEach { pullOnOpen(it) }
    }

    private fun pullOnOpen(p: Profile) = thread {
        try {
            if (!SyncEngine.remoteHasNews(p)) return@thread
            main.post { Toast.makeText(this, "OmniSync: updating ${p.name} from cloud…", Toast.LENGTH_SHORT).show() }
            val r = SyncEngine.sync(p, force = true, why = "app opened")
            if (r != null && r.changed) Root.launch(p.pkg)
        } catch (e: Exception) { Log.e("[${p.name}] pull-on-open", e) }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { foregroundPkg = null; super.onDestroy() }

    companion object {
        @Volatile var foregroundPkg: String? = null

        fun component(ctx: Context) = "${ctx.packageName}/${AppWatcherService::class.java.name}"

        fun isEnabled(ctx: Context): Boolean =
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { it.equals(component(ctx), true) } == true

        /** Enables the watcher through root (bypasses Android's restricted-settings block for sideloaded apps). */
        fun enableWithRoot(ctx: Context): Boolean {
            val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = (cur.split(':').filter { it.isNotBlank() } + component(ctx)).distinct().joinToString(":")
            return Root.run("settings put secure enabled_accessibility_services ${Root.q(list)} && settings put secure accessibility_enabled 1").ok
        }
    }
}

class SyncTileService : TileService() {
    override fun onClick() {
        Scheduler.oneShot(this, null, 0, "tile")
        Toast.makeText(this, "OmniSync: syncing all", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Automation entry point (shell/system only — protected by the DUMP permission):
 *   am broadcast -a dev.omnisync.SYNC -n dev.omnisync/.CommandReceiver [--es profile NAME] [--ez force true]
 *   am broadcast -a dev.omnisync.IMPORT -n dev.omnisync/.CommandReceiver --es json_b64 BASE64
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            "dev.omnisync.SYNC" -> {
                val prof = i.getStringExtra("profile")
                Scheduler.oneShot(ctx, prof, 0, "adb", i.getBooleanExtra("force", false))
                resultData = "queued ${prof ?: "all"}"
            }
            "dev.omnisync.IMPORT" -> try {
                val json = i.getStringExtra("json")
                    ?: String(Base64.decode(i.getStringExtra("json_b64").orEmpty(), Base64.DEFAULT))
                Store.import(json)
                Scheduler.scheduleAll(ctx)
                resultData = "imported ${Store.profiles().size} profiles"
                Log.i("Config imported via adb")
            } catch (e: Exception) { resultData = "error: ${e.message}"; Log.e("import", e) }
        }
    }
}

/** Starts the process after boot/update so the watcher and schedules are restored. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) { App.ensureWatcher(ctx) }
}
