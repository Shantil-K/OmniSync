package dev.omnisync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A cloud endpoint. */
data class Account(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var type: String = TYPE_WEBDAV,       // webdav | local
    var url: String = "",                 // webdav base URL, or local folder path
    var user: String = "",
    var pass: String = "",
) {
    companion object {
        const val TYPE_WEBDAV = "webdav"
        const val TYPE_LOCAL = "local"
    }

    fun toJson() = JSONObject().put("id", id).put("name", name).put("type", type).put("url", url)
        .put("user", user).put("pass", Crypto.enc(pass))

    fun label() = if (type == TYPE_LOCAL) "$name (folder)" else "$name (WebDAV)"
}

/** One thing to keep in sync. */
data class Profile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    /** FOLDER = two-way per file. SNAPSHOT = all files move together in one direction (app data / databases). */
    var mode: String = MODE_FOLDER,
    var localPath: String = "",
    var root: Boolean = false,
    /** Package to stop before touching its data (root + snapshot). */
    var pkg: String = "",
    var includes: List<String> = emptyList(),
    var excludes: List<String> = emptyList(),
    /** Paths deleted locally after pulling new data (stale caches). */
    var clearAfterPull: List<String> = emptyList(),
    var accountId: String = "",
    var remotePath: String = "",
    var intervalMin: Int = 15,
    var watchApp: Boolean = false,
    var enabled: Boolean = true,
    /** Snapshot conflicts: newest | local | remote. Folder conflicts always keep both copies. */
    var conflict: String = "newest",
) {
    companion object {
        const val MODE_FOLDER = "folder"
        const val MODE_SNAPSHOT = "snapshot"
        /** Samsung Notes: per-note three-way merge of the database + note files. */
        const val MODE_NOTES = "samsung-notes"
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("mode", mode); put("localPath", localPath)
        put("root", root); put("pkg", pkg)
        put("includes", JSONArray(includes)); put("excludes", JSONArray(excludes))
        put("clearAfterPull", JSONArray(clearAfterPull))
        put("accountId", accountId); put("remotePath", remotePath)
        put("intervalMin", intervalMin); put("watchApp", watchApp); put("enabled", enabled)
        put("conflict", conflict)
    }
}

object Presets {
    fun samsungNotes() = Profile(
        name = "Samsung Notes",
        mode = Profile.MODE_NOTES,
        localPath = "/data/data/com.samsung.android.app.notes",
        root = true,
        pkg = "com.samsung.android.app.notes",
        includes = listOf(
            "databases/sdoc.db", "databases/sdoc.db-wal",
            "databases/notes_extra.db", "databases/notes_extra.db-wal",
            "SDocData/**", "app_attach/**", "files/Voice/**", "files/bitmapImages/**",
        ),
        excludes = listOf("**/*-shm", "**/*-journal"),
        clearAfterPull = listOf("files/RendererCache", "cache"),
        remotePath = "OmniSync/SamsungNotes",
        watchApp = true,
        intervalMin = 15,
    )

    fun keepass() = Profile(
        name = "KeePass",
        mode = Profile.MODE_FOLDER,
        localPath = "/storage/emulated/0/Documents/KeePass",
        includes = listOf("**/*.kdbx", "**/*.key", "**/*.keyx"),
        remotePath = "OmniSync/KeePass",
        intervalMin = 15,
    )

    fun empty() = Profile(name = "New folder", localPath = "/storage/emulated/0/", remotePath = "OmniSync/Folder")
}

/** Config persisted as JSON in app-private storage. */
object Store {
    private val file get() = File(App.ctx.filesDir, "config.json")
    private var accounts = mutableListOf<Account>()
    private var profiles = mutableListOf<Profile>()
    private var loaded = false

    @Synchronized private fun ensure() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try { parse(JSONObject(file.readText()), encrypted = true) } catch (e: Exception) { Log.e("config load", e) }
    }

    private fun parse(o: JSONObject, encrypted: Boolean) {
        fun strs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
        accounts = o.optJSONArray("accounts")?.let { a ->
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                Account(j.getString("id"), j.optString("name"), j.optString("type", Account.TYPE_WEBDAV),
                    j.optString("url"), j.optString("user"),
                    j.optString("pass").let { if (encrypted) Crypto.dec(it) else it })
            }.toMutableList()
        } ?: mutableListOf()
        profiles = o.optJSONArray("profiles")?.let { a ->
            (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                Profile(
                    id = j.optString("id", UUID.randomUUID().toString()), name = j.optString("name"),
                    mode = j.optString("mode", Profile.MODE_FOLDER), localPath = j.optString("localPath"),
                    root = j.optBoolean("root"), pkg = j.optString("pkg"),
                    includes = strs(j.optJSONArray("includes")), excludes = strs(j.optJSONArray("excludes")),
                    clearAfterPull = strs(j.optJSONArray("clearAfterPull")),
                    accountId = j.optString("accountId"), remotePath = j.optString("remotePath"),
                    intervalMin = j.optInt("intervalMin", 15), watchApp = j.optBoolean("watchApp"),
                    enabled = j.optBoolean("enabled", true), conflict = j.optString("conflict", "newest"),
                )
            }.toMutableList()
        } ?: mutableListOf()
    }

    @Synchronized private fun save() {
        val o = JSONObject()
            .put("accounts", JSONArray(accounts.map { it.toJson() }))
            .put("profiles", JSONArray(profiles.map { it.toJson() }))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString(2)); tmp.renameTo(file)
    }

    @Synchronized fun accounts(): List<Account> { ensure(); return accounts.map { it.copy() } }
    @Synchronized fun profiles(): List<Profile> { ensure(); return profiles.map { it.copy() } }
    @Synchronized fun account(id: String) = accounts().find { it.id == id }
    @Synchronized fun profile(id: String) = profiles().find { it.id == id || it.name.equals(id, true) }

    @Synchronized fun put(a: Account) { ensure(); accounts.removeAll { it.id == a.id }; accounts.add(a); save() }
    @Synchronized fun put(p: Profile) {
        ensure(); val i = profiles.indexOfFirst { it.id == p.id }
        if (i >= 0) profiles[i] = p else profiles.add(p); save()
    }
    @Synchronized fun deleteAccount(id: String) { ensure(); accounts.removeAll { it.id == id }; save() }
    @Synchronized fun deleteProfile(id: String) {
        ensure(); profiles.removeAll { it.id == id }; save()
        SyncState.file(id).delete()
    }

    /** Import accounts/profiles from plain JSON (passwords in clear), merging by id. */
    @Synchronized fun import(json: String) {
        ensure()
        val oldA = accounts; val oldP = profiles
        parse(JSONObject(json), encrypted = false)
        val newA = accounts; val newP = profiles
        accounts = (oldA.filter { o -> newA.none { it.id == o.id } } + newA).toMutableList()
        profiles = (oldP.filter { o -> newP.none { it.id == o.id } } + newP).toMutableList()
        save()
    }

    /** Export everything (passwords in clear) for moving the setup to another device. */
    @Synchronized fun export(): String {
        ensure()
        return JSONObject()
            .put("accounts", JSONArray(accounts.map { it.toJson().put("pass", it.pass) }))
            .put("profiles", JSONArray(profiles.map { it.toJson() }))
            .toString(2)
    }
}

/** AES-GCM with a non-exportable Android Keystore key, for stored passwords. */
object Crypto {
    private const val ALIAS = "omnisync-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun enc(s: String): String {
        if (s.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    fun dec(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            val b = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
            String(c.doFinal(b, 12, b.size - 12))
        } catch (e: Exception) { Log.e("password decrypt", e); "" }
    }
}
