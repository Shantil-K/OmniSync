package dev.omnisync

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Sync problems & conflicts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        Scheduler.scheduleAll(this)
        ensureWatcher(this)
    }

    companion object {
        /** Root mode: the system drops a sideloaded app's accessibility service on update; put it back. */
        fun ensureWatcher(c: Context) = kotlin.concurrent.thread {
            try {
                if (!Root.available()) return@thread
                // Keep background sync alive: battery-optimisation exemption, background-run, and
                // HyperOS/MIUI "Autostart" (app-op 10008; harmless elsewhere).
                val pk = Root.q(c.packageName)
                Root.run("dumpsys deviceidle whitelist +$pk >/dev/null 2>&1; cmd appops set $pk RUN_ANY_IN_BACKGROUND allow; " +
                    "cmd appops set $pk 10008 allow 2>/dev/null; true")
                if (Store.profiles().any { it.enabled && it.watchApp } && !AppWatcherService.isEnabled(c)) {
                    if (AppWatcherService.enableWithRoot(c)) Log.i("App watcher re-enabled via root")
                }
            } catch (e: Exception) { Log.e("ensureWatcher", e) }
        }

        const val CH_ALERTS = "alerts"
        lateinit var ctx: Context
            private set

        val deviceName: String by lazy {
            (Settings.Global.getString(ctx.contentResolver, "device_name") ?: Build.MODEL)
                .replace(Regex("[^A-Za-z0-9 _.-]"), "").trim().ifEmpty { Build.MODEL }
        }

        fun notify(id: Int, title: String, text: String) {
            val n = NotificationCompat.Builder(ctx, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
        }
    }
}

/** Tiny persistent log (files/omnisync.log), viewable in the app. */
object Log {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val file get() = File(App.ctx.filesDir, "omnisync.log")

    @Synchronized
    fun i(msg: String) {
        android.util.Log.i("OmniSync", msg)
        try {
            val f = file
            if (f.length() > 512 * 1024) {
                val tail = f.readText().takeLast(256 * 1024)
                f.writeText(tail)
            }
            f.appendText("${fmt.format(Date())}  $msg\n")
        } catch (_: Exception) {}
    }

    fun e(msg: String, t: Throwable? = null) = i("ERROR $msg${t?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")

    fun read(): String = try { file.readText() } catch (_: Exception) { "" }
    fun clear() { file.delete() }
}
