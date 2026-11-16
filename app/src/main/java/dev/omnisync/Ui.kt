package dev.omnisync

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.content.res.Configuration
import android.util.TypedValue
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.concurrent.thread

// ---- tiny view DSL ------------------------------------------------------------------------------

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.col(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; block() }
fun Context.row(block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; block() }

/** A full-width horizontal row added to this layout (the Context version only creates one). */
fun LinearLayout.row(block: LinearLayout.() -> Unit = {}): LinearLayout =
    context.row(block).also { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

private fun Context.attrColor(attr: Int): Int {
    val tv = TypedValue(); theme.resolveAttribute(attr, tv, true); return tv.data
}

fun LinearLayout.text(s: String, size: Float = 14f, bold: Boolean = false, muted: Boolean = false, block: TextView.() -> Unit = {}) =
    TextView(context).apply {
        text = s; textSize = size; if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(context.attrColor(if (muted) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
        setPadding(0, context.dp(3), 0, context.dp(3)); block()
    }.also { addView(it) }

fun LinearLayout.section(s: String) = text(s, 13f, true) {
    setTextColor(context.attrColor(androidx.appcompat.R.attr.colorPrimary)); setPadding(0, context.dp(18), 0, context.dp(6))
}

private fun LinearLayout.mbtn(s: String, outlined: Boolean, onClick: () -> Unit) =
    (if (outlined) MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle) else MaterialButton(context))
        .apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }

/** Equal-width button in a row; the first in a row is filled, the rest outlined unless [primary] says otherwise. */
fun LinearLayout.button(s: String, primary: Boolean = childCount == 0, onClick: () -> Unit) =
    mbtn(s, !primary, onClick).also {
        addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (childCount > 0) marginStart = context.dp(8)
        })
    }

fun LinearLayout.wideButton(s: String, primary: Boolean = false, onClick: () -> Unit) =
    mbtn(s, !primary, onClick).also { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

fun LinearLayout.field(label: String, value: String, hint: String = "", multi: Boolean = false, password: Boolean = false, number: Boolean = false): EditText {
    val til = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
        this.hint = label
        if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        if (hint.isNotEmpty()) placeholderText = hint
    }
    val et = TextInputEditText(til.context).apply {
        setText(value)
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_URI
        }
        if (multi) { minLines = 2; gravity = Gravity.TOP or Gravity.START }
    }
    til.addView(et)
    addView(til, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(10) })
    return et
}

fun LinearLayout.switch(label: String, on: Boolean, sub: String = "", onChange: (Boolean) -> Unit = {}) {
    val r = context.row { setPadding(0, context.dp(10), 0, context.dp(6)) }
    val texts = context.col {
        text(label, 15f)
        if (sub.isNotEmpty()) text(sub, 12f, muted = true)
    }
    r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val sw = MaterialSwitch(context).apply { isChecked = on; setOnCheckedChangeListener { _, c -> onChange(c) } }
    r.addView(sw)
    r.setOnClickListener { sw.toggle() }
    addView(r)
}

fun LinearLayout.spinner(label: String, items: List<String>, sel: Int, onSel: (Int) -> Unit = {}): Spinner {
    text(label, 12f, muted = true) { setPadding(0, context.dp(12), 0, 0) }
    return Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(sel.coerceIn(0, maxOf(0, items.size - 1)))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSel(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }.also { addView(it) }
}

/**
 * Screen scaffold: a top app bar below the status bar, scrolling content padded for the system
 * bars / display cutout / keyboard (Android 15+ always draws edge-to-edge), width-capped on tablets.
 */
fun AppCompatActivity.page(title: String, back: Boolean = this !is MainActivity, block: LinearLayout.() -> Unit) {
    val oldScroll = (window.decorView.findViewWithTag<ScrollView>("page-scroll"))?.scrollY ?: 0
    val bar = MaterialToolbar(this).apply {
        this.title = title
        if (back) {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
    }
    val content = col { setPadding(dp(16), dp(4), dp(16), dp(24)); block() }
    val maxW = dp(760)
    val holder = FrameLayout(this).apply {
        addView(content, FrameLayout.LayoutParams(
            if (resources.displayMetrics.widthPixels > maxW + dp(32)) maxW else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
    }
    val sv = ScrollView(this).apply { tag = "page-scroll"; clipToPadding = false; addView(holder) }
    val root = col {
        setBackgroundColor(attrColor(com.google.android.material.R.attr.colorSurface))
        addView(bar)
        addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
        val sys = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = ins.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(sys.left, sys.top, sys.right, 0)
        sv.setPadding(0, 0, 0, maxOf(sys.bottom, ime.bottom))
        WindowInsetsCompat.CONSUMED
    }
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night; isAppearanceLightNavigationBars = !night
    }
    WindowCompat.setDecorFitsSystemWindows(window, false)
    setContentView(root)
    ViewCompat.requestApplyInsets(root)
    sv.post { sv.scrollTo(0, oldScroll) }
}

fun LinearLayout.card(block: LinearLayout.() -> Unit) {
    val card = MaterialCardView(context).apply { radius = context.dp(16).toFloat() }
    card.addView(context.col { setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(14)); block() })
    addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(12) })
}

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

// ---- screens ------------------------------------------------------------------------------------

class MainActivity : AppCompatActivity() {
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); h.postDelayed(this, 2000) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        thread { Root.available(true); runOnUiThread { render() } }
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    @SuppressLint("SetTextI18n")
    private fun render(): Unit = page("OmniSync") {
        val root = Root.available()
        val watcher = AppWatcherService.isEnabled(this@MainActivity)
        text("${App.deviceName}  ·  Root ${if (root) "✓" else "✗"}  ·  Watcher ${if (watcher) "✓" else "off"}", 13f, muted = true) {
            setPadding(0, 0, 0, dp(10))
        }
        row {
            button("Sync all") { Scheduler.oneShot(this@MainActivity, null, 0, "manual"); toast("Syncing…") }
            button("+ Profile") { startActivity(Intent(this@MainActivity, ProfileActivity::class.java)) }
        }
        row {
            setPadding(0, dp(4), 0, dp(8))
            button("Accounts", primary = false) { startActivity(Intent(this@MainActivity, AccountsActivity::class.java)) }
            button("Log", primary = false) { startActivity(Intent(this@MainActivity, LogActivity::class.java)) }
        }
        if (!Store.accounts().any()) card {
            text("Step 1 — add a cloud account", 16f, true)
            text("WebDAV works with Nextcloud, ownCloud, Koofr (which can also link Google Drive, OneDrive and Dropbox), pCloud, Box, InfiniCLOUD, a NAS, or any self-hosted server. Or use a plain folder that another sync app uploads.", 13f, muted = true)
            wideButton("Add account", primary = true) { startActivity(Intent(this@MainActivity, AccountsActivity::class.java)) }
        }
        val needWatcher = Store.profiles().any { it.watchApp && it.enabled }
        if (needWatcher && !watcher) card {
            text("App watcher is off", 16f, true)
            text("Needed to push right after you close a watched app and to pull before you open it. Without it, sync still runs every interval.", 13f, muted = true)
            row {
                setPadding(0, dp(8), 0, 0)
                if (root) button("Enable (root)") {
                    thread { val ok = AppWatcherService.enableWithRoot(this@MainActivity); runOnUiThread { toast(if (ok) "Watcher enabled" else "Failed"); render() } }
                }
                button("Open settings", primary = !root) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }
        if (Store.profiles().any { !it.root } && !Environment.isExternalStorageManager()) card {
            text("Storage access needed", 16f, true)
            text("Non-root folder profiles need \"All files access\".", 13f, muted = true)
            wideButton("Grant", primary = true) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        if (Store.profiles().isNotEmpty()) section("Profiles")
        for (p in Store.profiles()) card {
            val st = SyncState.load(p.id)
            val acc = Store.account(p.accountId)
            text(p.name + if (!p.enabled) "  (paused)" else "", 18f, true)
            text("${when (p.mode) { Profile.MODE_SNAPSHOT -> "App data snapshot"; Profile.MODE_NOTES -> "Per-note merge"; else -> "Two-way folder" }}${if (p.root) " · root" else ""} · every ${p.intervalMin} min${if (p.watchApp) " · watches app" else ""}", 12f, muted = true)
            text("${p.localPath}\n⇄ ${acc?.label() ?: "⚠ no account"} : /${p.remotePath.trim('/')}", 12f, muted = true) {
                typeface = Typeface.MONOSPACE; setPadding(0, dp(6), 0, dp(6))
            }
            val status = when {
                p.id in SyncEngine.running -> "⟳ Syncing…"
                st.lastTime == 0L -> "Never synced"
                else -> "${if (st.lastOk) "✓" else "✗"} ${DateUtils.getRelativeTimeSpanString(st.lastTime)} — ${st.lastResult}"
            }
            text(status, 14f) { if (!st.lastOk) setTextColor(0xFFE53935.toInt()) }
            row {
                setPadding(0, dp(8), 0, 0)
                button("Sync now") { Scheduler.oneShot(this@MainActivity, p.id, 0, "manual", force = true); toast("Syncing ${p.name}…") }
                button("Edit") { startActivity(Intent(this@MainActivity, ProfileActivity::class.java).putExtra("id", p.id)) }
            }
        }
        if (Store.profiles().isEmpty()) text("No profiles yet. Tap \"+ Profile\" and pick a preset (Samsung Notes, KeePass) or any folder.", 14f, muted = true)
        text("Tip: add the \"Sync now\" Quick Settings tile. Settings can be exported from Accounts to set up another device.", 12f, muted = true) {
            setPadding(0, dp(12), 0, 0)
        }
    }
}

class ProfileActivity : AppCompatActivity() {
    private lateinit var p: Profile

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val existing = intent.getStringExtra("id")?.let { Store.profile(it) }
        if (existing != null) { p = existing; form() }
        else AlertDialog.Builder(this).setTitle("New profile from…")
            .setItems(arrayOf("Samsung Notes (root, app data)", "KeePass database folder", "Any folder (two-way)")) { _, w ->
                p = when (w) { 0 -> Presets.samsungNotes(); 1 -> Presets.keepass(); else -> Presets.empty() }
                Store.accounts().firstOrNull()?.let { p.accountId = it.id }
                form()
            }
            .setOnCancelListener { finish() }.show()
    }

    private fun form(): Unit = page(if (Store.profile(p.id) == null) "New profile" else "Edit profile") {
        section("General")
        val name = field("Name", p.name)
        val accounts = Store.accounts()
        var accIdx = accounts.indexOfFirst { it.id == p.accountId }.coerceAtLeast(0)
        if (accounts.isEmpty()) text("⚠ Add a cloud account first (Accounts screen).") { setTextColor(0xFFC62828.toInt()) }
        else spinner("Cloud account", accounts.map { it.label() }, accIdx) { accIdx = it }
        val remote = field("Cloud folder", p.remotePath, "OmniSync/MyFolder")
        section("This device")
        val local = field("Local folder", p.localPath, "/storage/emulated/0/Documents")
        var mode = p.mode
        val modes = listOf(Profile.MODE_FOLDER, Profile.MODE_SNAPSHOT, Profile.MODE_NOTES)
        spinner("Mode", listOf("Two-way folder (per-file, keeps both on conflict)",
            "App data snapshot (all-or-nothing, for databases)",
            "Samsung Notes merge (per-note two-way)"),
            modes.indexOf(p.mode).coerceAtLeast(0)) { mode = modes[it] }
        var rootMode = p.root
        switch("Root mode", p.root, "Access any path (e.g. /data/data/<app>) via su and fix owner + SELinux label") { rootMode = it }
        val pkg = field("App package (root: stopped before its data is touched)", p.pkg, "com.example.app")
        var watch = p.watchApp
        switch("Watch app", p.watchApp, "Push when the app is closed, pull before it opens (needs app watcher)") { watch = it }
        section("Files")
        val inc = field("Include (one glob per line; empty = everything)", p.includes.joinToString("\n"), "**/*.kdbx", multi = true)
        val exc = field("Exclude (one glob per line)", p.excludes.joinToString("\n"), "**/*.tmp", multi = true)
        val clr = field("Clear after pulling (stale caches)", p.clearAfterPull.joinToString("\n"), multi = true)
        section("Schedule & conflicts")
        val interval = field("Sync every (minutes, ≥15)", p.intervalMin.toString(), number = true)
        val policies = listOf("newest", "local", "remote")
        var pol = policies.indexOf(p.conflict).coerceAtLeast(0)
        spinner("Snapshot conflict winner (loser is backed up)", listOf("Newest change wins", "This device wins", "Cloud wins"), pol) { pol = it }
        var enabled = p.enabled
        switch("Enabled", p.enabled) { enabled = it }

        row {
            setPadding(0, dp(16), 0, 0)
            button("Save") {
                fun lines(e: EditText) = e.text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                p = p.copy(
                    name = name.text.toString().trim().ifEmpty { "Profile" },
                    accountId = accounts.getOrNull(accIdx)?.id ?: "",
                    remotePath = remote.text.toString().trim().trim('/'),
                    localPath = local.text.toString().trim().trimEnd('/'),
                    mode = mode, root = rootMode, pkg = pkg.text.toString().trim(), watchApp = watch,
                    includes = lines(inc), excludes = lines(exc), clearAfterPull = lines(clr),
                    intervalMin = interval.text.toString().toIntOrNull()?.coerceAtLeast(15) ?: 15,
                    conflict = policies[pol], enabled = enabled,
                )
                Store.put(p); Scheduler.scheduleAll(this@ProfileActivity); finish()
            }
            if (Store.profile(p.id) != null) button("Delete") {
                AlertDialog.Builder(this@ProfileActivity).setMessage("Delete profile \"${p.name}\"? Files are not touched.")
                    .setPositiveButton("Delete") { _, _ -> Store.deleteProfile(p.id); Scheduler.scheduleAll(this@ProfileActivity); finish() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }
}

class AccountsActivity : AppCompatActivity() {
    override fun onResume() { super.onResume(); render() }

    private fun render(): Unit = page("Cloud accounts") {
        for (a in Store.accounts()) card {
            text(a.label(), 16f, true)
            text(a.url + if (a.user.isNotEmpty()) "  ·  ${a.user}" else "", 12f)
            row {
                button("Edit") { edit(a) }
                button("Test") { test(a) }
                button("Delete") { Store.deleteAccount(a.id); render() }
            }
        }
        wideButton("+ Add WebDAV account") { edit(Account(type = Account.TYPE_WEBDAV)) }
        wideButton("+ Add folder (USB/SD/other sync app)") { edit(Account(type = Account.TYPE_LOCAL, name = "Folder", url = "/storage/emulated/0/OmniSyncCloud")) }
        section("Move your setup to another device")
        row {
            button("Export (copy)") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("OmniSync config", Store.export()))
                toast("Config (incl. passwords) copied")
            }
            button("Import (paste)") {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                val s = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                try { Store.import(s); Scheduler.scheduleAll(this@AccountsActivity); toast("Imported"); render() }
                catch (e: Exception) { toast("Clipboard is not an OmniSync config") }
            }
        }
    }

    private fun test(a: Account) { thread {
        val msg = try {
            val r = Remote.of(a, "")
            val n = r.list().size
            "OK — connected ($n files visible)"
        } catch (e: Exception) { "Failed: ${e.message}" }
        runOnUiThread { AlertDialog.Builder(this).setTitle(a.name).setMessage(msg).setPositiveButton("OK", null).show() }
    } }

    private fun edit(a: Account) {
        val web = a.type == Account.TYPE_WEBDAV
        lateinit var name: EditText; lateinit var url: EditText; var user: EditText? = null; var pass: EditText? = null
        val v = col {
            setPadding(dp(20), dp(8), dp(20), 0)
            name = field("Name", a.name, if (web) "Nextcloud" else "Folder")
            url = field(if (web) "WebDAV URL" else "Folder path", a.url,
                if (web) "https://cloud.example.com/remote.php/dav/files/USER/" else "/storage/emulated/0/OmniSyncCloud")
            if (web) {
                user = field("Username", a.user)
                pass = field("Password / app password", a.pass, password = true)
            }
        }
        AlertDialog.Builder(this).setTitle(if (web) "WebDAV account" else "Folder").setView(ScrollView(this).apply { addView(v) })
            .setPositiveButton("Save") { _, _ ->
                Store.put(a.copy(name = name.text.toString().trim().ifEmpty { if (web) "WebDAV" else "Folder" },
                    url = url.text.toString().trim(), user = user?.text?.toString()?.trim() ?: "", pass = pass?.text?.toString() ?: ""))
                render()
            }
            .setNegativeButton("Cancel", null).show()
    }
}

class LogActivity : AppCompatActivity() {
    override fun onCreate(b: Bundle?) { super.onCreate(b); render() }
    private fun render(): Unit = page("Log") {
        row {
            button("Refresh") { render() }
            button("Clear") { Log.clear(); render() }
        }
        text(Log.read().lines().takeLast(400).reversed().joinToString("\n"), 11f, muted = true) {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        }
    }
}
