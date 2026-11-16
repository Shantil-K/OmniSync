package dev.omnisync

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** What this device last agreed with the cloud on, per profile. */
class SyncState(val profileId: String) {
    /** hash = content both sides had at last sync; r* = remote stat then; l* = local stat then. */
    data class Entry(val hash: String, val rsize: Long, val rtag: String, val lsize: Long, val lmtime: Long)

    var rev = 0L
    var entries = HashMap<String, Entry>()
    var lastTime = 0L
    var lastResult = ""
    var lastOk = true
    /** Samsung Notes merge: per-note fingerprint both sides had at last sync. */
    var notes = HashMap<String, String>()

    fun save() {
        val e = JSONObject()
        entries.forEach { (k, v) ->
            e.put(k, JSONObject().put("h", v.hash).put("rs", v.rsize).put("rt", v.rtag).put("ls", v.lsize).put("lm", v.lmtime))
        }
        val o = JSONObject().put("rev", rev).put("entries", e).put("lastTime", lastTime)
            .put("lastResult", lastResult).put("lastOk", lastOk).put("notes", JSONObject(notes as Map<*, *>))
        val f = file(profileId); f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
    }

    companion object {
        fun file(id: String) = File(App.ctx.filesDir, "state/$id.json")
        fun load(id: String): SyncState {
            val s = SyncState(id)
            val f = file(id)
            if (!f.exists()) return s
            try {
                val o = JSONObject(f.readText())
                s.rev = o.optLong("rev"); s.lastTime = o.optLong("lastTime")
                s.lastResult = o.optString("lastResult"); s.lastOk = o.optBoolean("lastOk", true)
                o.optJSONObject("notes")?.let { n -> n.keys().forEach { k -> s.notes[k] = n.getString(k) } }
                val e = o.getJSONObject("entries")
                e.keys().forEach { k ->
                    val j = e.getJSONObject(k)
                    s.entries[k] = Entry(j.getString("h"), j.optLong("rs"), j.optString("rt"), j.optLong("ls", -1), j.optLong("lm", -1))
                }
            } catch (ex: Exception) { Log.e("state load $id", ex) }
            return s
        }
    }
}

/** Shared cloud-side index: content hashes keyed by the remote change tag, plus a revision counter. */
class Manifest(var rev: Long = 0, var device: String = "", var time: Long = 0) {
    data class F(val hash: String, val size: Long, val tag: String)
    val files = HashMap<String, F>()
    var exists = false

    fun bytes(): ByteArray {
        val fs = JSONObject()
        files.forEach { (k, v) -> fs.put(k, JSONObject().put("h", v.hash).put("s", v.size).put("t", v.tag)) }
        return JSONObject().put("rev", rev).put("device", device).put("time", time).put("files", fs).toString().toByteArray()
    }

    companion object {
        fun read(r: Remote): Manifest {
            val b = r.readMeta("manifest.json") ?: return Manifest()
            val o = JSONObject(String(b))
            val m = Manifest(o.optLong("rev"), o.optString("device"), o.optLong("time"))
            m.exists = true
            o.optJSONObject("files")?.let { fs ->
                fs.keys().forEach { k -> val j = fs.getJSONObject(k); m.files[k] = F(j.getString("h"), j.optLong("s"), j.optString("t")) }
            }
            return m
        }
    }
}

data class SyncResult(
    var up: Int = 0, var down: Int = 0, var delRemote: Int = 0, var delLocal: Int = 0,
    var conflicts: Int = 0, var bytes: Long = 0, var note: String = "",
) {
    val changed get() = up + down + delRemote + delLocal > 0
    override fun toString(): String {
        if (note.isNotEmpty() && !changed) return note
        if (!changed) return "Up to date"
        val parts = mutableListOf<String>()
        if (up > 0) parts += "↑$up"; if (down > 0) parts += "↓$down"
        if (delRemote > 0) parts += "cloud −$delRemote"; if (delLocal > 0) parts += "local −$delLocal"
        if (conflicts > 0) parts += "$conflicts conflict(s)"
        return parts.joinToString(", ") + " (${fmtBytes(bytes)})" + if (note.isNotEmpty()) " · $note" else ""
    }

    companion object {
        fun fmtBytes(b: Long) = when {
            b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
            b >= 1024 -> "%.0f KB".format(b / 1024.0)
            else -> "$b B"
        }
    }
}

class SyncEngine(private val p: Profile, private val force: Boolean = false) {
    private val remote: Remote
    private val local = LocalFs.of(p)
    private val filter = PathFilter(p.includes, p.excludes)
    private val state = SyncState.load(p.id)
    private val tmp = File(App.ctx.cacheDir, "sync-${p.id}")
    private val res = SyncResult()
    private val notesMode = p.mode == Profile.MODE_NOTES
    private val t0 = System.currentTimeMillis()
    private fun phase(n: String) = Log.i("[${p.name}]   ${System.currentTimeMillis() - t0} ms: $n")

    init {
        val acc = Store.account(p.accountId) ?: throw IOException("No cloud account selected for '${p.name}'")
        remote = Remote.of(acc, p.remotePath)
    }

    private enum class Dir { NONE, PUSH, PULL, MERGE }

    /** Everything we'll do in one run. Keys are relative paths. */
    private class Plan {
        val upload = LinkedHashSet<String>()
        val download = LinkedHashSet<String>()
        val delRemote = LinkedHashSet<String>()
        val delLocal = LinkedHashSet<String>()
        /** conflict copy name -> original rel whose LOCAL content becomes the copy */
        val conflictCopies = LinkedHashMap<String, String>()
        var snapshotDir = Dir.NONE
        var snapshotConflict = false
        val empty get() = snapshotDir != Dir.MERGE && upload.isEmpty() && download.isEmpty() && delRemote.isEmpty() && delLocal.isEmpty()
    }

    fun run(): SyncResult {
        tmp.deleteRecursively(); tmp.mkdirs()
        try {
            if (!local.exists()) {
                if (!p.root) File(p.localPath).mkdirs()
                if (!local.exists()) throw IOException("Local folder not found: ${p.localPath}")
            }
            acquireLock()
            try { doSync() } finally { releaseLock() }
        } finally {
            tmp.deleteRecursively()
        }
        return res
    }

    // ---- locking (cross-device) -------------------------------------------------------------

    private fun acquireLock() {
        remote.readMeta("lock")?.let {
            val o = JSONObject(String(it))
            val age = System.currentTimeMillis() - o.optLong("time")
            if (o.optString("device") != App.deviceName && age in 0..10 * 60_000)
                throw IOException("Cloud is busy: ${o.optString("device")} is syncing (retry soon)")
        }
        remote.writeMeta("lock", JSONObject().put("device", App.deviceName).put("time", System.currentTimeMillis()).toString().toByteArray())
    }

    private fun releaseLock() = try { remote.deleteMeta("lock") } catch (e: Exception) { Log.e("unlock", e) }

    // ---- main ---------------------------------------------------------------------------------

    private lateinit var manifest: Manifest
    private lateinit var rlist: Map<String, RStat>
    private val rhash = HashMap<String, String>()
    private val downloaded = HashMap<String, File>()
    private lateinit var lscan: Map<String, LEntry>

    private fun doSync() {
        manifest = Manifest.read(remote)
        rlist = remote.list().filterKeys(filter::accept)
        phase("listed cloud (${rlist.size})")
        resolveRemoteHashes()
        phase("cloud hashes known (${downloaded.size} fetched)")
        lscan = local.scan(filter, state.entries)
        phase("scanned local (${lscan.size})")
        var plan = plan()

        val appGuard = (p.mode == Profile.MODE_SNAPSHOT || notesMode) && p.root && p.pkg.isNotBlank()
        if (appGuard && !plan.empty) {
            if (!force && Root.isForeground(p.pkg)) {
                res.note = "Waiting: ${p.pkg.substringAfterLast('.')} is open"
                Log.i("[${p.name}] deferred, app in foreground")
                return
            }
            // Stop the app so its database is quiescent, then look again.
            Root.forceStop(p.pkg)
            Thread.sleep(400)
            lscan = local.scan(filter, state.entries)
            plan = plan()
        }

        if (plan.empty) {
            saveState(emptyMap())
            if (notesMode && state.notes.isEmpty() && rlist.isNotEmpty()) updateMirrorAndFingerprints()
            return
        }
        if (plan.snapshotDir == Dir.MERGE) merge() else execute(plan)
        if (notesMode) updateMirrorAndFingerprints(plan)
    }

    /** Hashes of cloud files: from the manifest/state when the tag is unchanged, else by downloading. */
    /** Runs network transfers 4 at a time. */
    private fun <T> parallel(items: Collection<T>, work: (T) -> Unit) {
        if (items.isEmpty()) return
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, items.size))
        try {
            items.map { pool.submit { work(it) } }.forEach { it.get() }
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: e
        } finally { pool.shutdownNow() }
    }

    private fun resolveRemoteHashes() {
        val unknown = ArrayList<String>()
        for ((rel, st) in rlist) {
            val m = manifest.files[rel]
            val b = state.entries[rel]
            val h = when {
                m != null && m.tag == st.tag && m.size == st.size && st.tag.isNotEmpty() -> m.hash
                b != null && b.rtag == st.tag && b.rsize == st.size && st.tag.isNotEmpty() -> b.hash
                else -> null
            }
            if (h != null) { rhash[rel] = h; continue }
            unknown += rel
        }
        // Changed outside OmniSync (e.g. KeePass on a PC) — fetch to learn the content.
        parallel(unknown) { rel ->
            val f = File(tmp, "dl/$rel"); remote.download(rel, f)
            val h = LocalFs.sha256(f)
            synchronized(this) { rhash[rel] = h; downloaded[rel] = f; res.bytes += f.length() }
        }
    }

    private fun plan(): Plan {
        val pl = Plan()
        val all = (lscan.keys + rlist.keys + state.entries.keys).toSortedSet()
        if (p.mode == Profile.MODE_SNAPSHOT || notesMode) {
            val firstSync = state.entries.isEmpty() && state.rev == 0L
            val localChanged = all.filter { lscan[it]?.hash != state.entries[it]?.hash }
            val remoteChanged = all.filter { rhash[it] != state.entries[it]?.hash }
            val differs = all.any { lscan[it]?.hash != rhash[it] }
            pl.snapshotDir = when {
                !differs -> Dir.NONE
                firstSync && notesMode -> when {
                    rlist.isEmpty() -> Dir.PUSH
                    lscan.keys.none { it.startsWith("databases/sdoc.db") } -> Dir.PULL
                    else -> Dir.MERGE
                }
                firstSync -> if (rlist.isEmpty()) Dir.PUSH else { pl.snapshotConflict = lscan.isNotEmpty(); Dir.PULL }
                // Notes: anything coming from the cloud is merged per note (never a blind
                // overwrite), so a note that only exists here can't be lost.
                notesMode && remoteChanged.isNotEmpty() && lscan.keys.any { it.startsWith("databases/sdoc.db") } -> Dir.MERGE
                localChanged.isNotEmpty() && remoteChanged.isNotEmpty() -> {
                    pl.snapshotConflict = true
                    when (p.conflict) {
                        "local" -> Dir.PUSH
                        "remote" -> Dir.PULL
                        else -> {
                            val lt = localChanged.mapNotNull { lscan[it]?.mtime }.maxOrNull() ?: 0L
                            val rt = if (manifest.time > 0) manifest.time else System.currentTimeMillis()
                            if (lt > rt) Dir.PUSH else Dir.PULL
                        }
                    }
                }
                localChanged.isNotEmpty() -> Dir.PUSH
                remoteChanged.isNotEmpty() -> Dir.PULL
                else -> Dir.NONE
            }
            for (rel in all) {
                val l = lscan[rel]?.hash; val r = rhash[rel]
                if (l == r) continue
                when (pl.snapshotDir) {
                    Dir.PUSH -> if (l != null) pl.upload += rel else pl.delRemote += rel
                    Dir.PULL -> if (r != null) pl.download += rel else pl.delLocal += rel
                    Dir.MERGE, Dir.NONE -> {}
                }
            }
            return pl
        }

        // FOLDER: independent two-way decision per file.
        for (rel in all) {
            val l = lscan[rel]?.hash; val r = rhash[rel]; val b = state.entries[rel]?.hash
            when {
                l == r -> {}
                l == b -> if (r == null) pl.delLocal += rel else pl.download += rel
                r == b -> if (l == null) pl.delRemote += rel else pl.upload += rel
                l == null -> pl.download += rel     // deleted here, edited there: keep the edit
                r == null -> pl.upload += rel       // edited here, deleted there: keep the edit
                else -> {                           // edited on both sides: keep both
                    val copy = conflictName(rel)
                    pl.conflictCopies[copy] = rel
                    pl.download += rel
                    pl.upload += copy
                }
            }
        }
        return pl
    }

    private fun conflictName(rel: String): String {
        val dir = rel.substringBeforeLast('/', "")
        val name = rel.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        val ts = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
        val n = if (dot > 0) "${name.substring(0, dot)} (conflict ${App.deviceName} $ts)${name.substring(dot)}"
                else "$name (conflict ${App.deviceName} $ts)"
        return if (dir.isEmpty()) n else "$dir/$n"
    }

    private fun execute(pl: Plan) {
        Log.i("[${p.name}] plan: up=${pl.upload.size} down=${pl.download.size} delCloud=${pl.delRemote.size} delLocal=${pl.delLocal.size}" +
            if (p.mode != Profile.MODE_FOLDER) " dir=${pl.snapshotDir}${if (pl.snapshotConflict) " (conflict)" else ""}" else "")

        // 1. Back up whatever is about to be overwritten when both sides had changes.
        if (pl.snapshotConflict) backup(pl)

        // 2. Download into staging (nothing local is touched until everything arrived).
        parallel(pl.download.filter { it !in downloaded }) { rel ->
            val f = File(tmp, "dl/$rel"); remote.download(rel, f)
            val h = LocalFs.sha256(f)
            synchronized(this) { downloaded[rel] = f; res.bytes += f.length(); rhash[rel] = h }
        }

        // 3. Upload (local content first copied out, via root if needed).
        val upSrc = File(tmp, "up")
        val needRead = pl.upload.map { pl.conflictCopies[it] ?: it }.toSet()
        local.readTo(needRead, upSrc)
        val newTags = HashMap<String, RStat>()
        parallel(pl.upload) { rel ->
            val src = File(upSrc, pl.conflictCopies[rel] ?: rel)
            remote.upload(rel, src)
            val st = remote.stat(rel) ?: RStat(src.length(), "")
            synchronized(this) { res.bytes += src.length(); newTags[rel] = st }
        }
        for (rel in pl.delRemote) remote.delete(rel)

        // 4. Install downloads + conflict copies locally, delete what the cloud deleted.
        val writes = LinkedHashMap<String, File>()
        pl.download.forEach { writes[it] = downloaded[it]!! }
        pl.conflictCopies.forEach { (copy, orig) -> writes[copy] = File(upSrc, orig) }
        val pulled = pl.download.isNotEmpty() || pl.delLocal.isNotEmpty()
        guardApp { local.apply(writes, pl.delLocal, if (pulled && p.mode != Profile.MODE_FOLDER) p.clearAfterPull else emptyList()) }

        res.up = pl.upload.size; res.down = pl.download.size
        res.delRemote = pl.delRemote.size; res.delLocal = pl.delLocal.size
        res.conflicts = pl.conflictCopies.size + if (pl.snapshotConflict) 1 else 0
        if (res.conflicts > 0) {
            val msg = if (p.mode == Profile.MODE_SNAPSHOT)
                "${p.name} changed on this device and in the cloud. Kept the ${if (pl.snapshotDir == Dir.PUSH) "local" else "cloud"} version; the other was backed up."
            else "${pl.conflictCopies.size} file(s) in ${p.name} were edited on two devices. Both versions were kept (look for \"(conflict …)\")."
            App.notify(p.id.hashCode(), "Sync conflict", msg)
        }

        saveState(newTags, pl)
        lastUpSrc = upSrc
    }

    // ---- Samsung Notes: mirror of the cloud copy + per-note merge ------------------------------

    private var lastUpSrc: File? = null
    private val mirror get() = File(App.ctx.filesDir, "mirror/${p.id}")
    private val mirrorIndexFile get() = File(App.ctx.filesDir, "mirror/${p.id}.json")

    private fun mirrorIndex(): HashMap<String, String> {
        val m = HashMap<String, String>()
        try { val o = JSONObject(mirrorIndexFile.readText()); o.keys().forEach { m[it] = o.getString(it) } } catch (_: Exception) {}
        return m
    }

    /** Makes the mirror equal to the cloud, fetching only what it lacks. */
    private fun syncMirror(extraSources: List<File> = emptyList()) {
        val idx = mirrorIndex()
        val cloud = HashMap<String, String>()
        for (rel in rlistNow().keys) cloud[rel] = hashNow(rel) ?: continue
        val fetch = ArrayList<String>()
        for ((rel, h) in cloud) {
            val f = File(mirror, rel)
            if (idx[rel] == h && f.isFile) continue
            val src = (listOf(downloaded[rel]) + extraSources.map { File(it, rel) }).firstOrNull { it != null && it.isFile && LocalFs.sha256(it) == h }
            if (src != null) { src.copyTo(f, true); idx[rel] = h } else fetch += rel
        }
        parallel(fetch) { rel ->
            val f = File(mirror, rel); remote.download(rel, f)
            synchronized(this) { res.bytes += f.length(); idx[rel] = cloud[rel]!! }
        }
        (idx.keys - cloud.keys).toList().forEach { File(mirror, it).delete(); idx.remove(it) }
        mirrorIndexFile.parentFile?.mkdirs()
        mirrorIndexFile.writeText(JSONObject(idx as Map<*, *>).toString())
    }

    /** Final cloud state as recorded in the manifest we just wrote. */
    private var finalCloud: Map<String, Manifest.F> = emptyMap()
    private fun rlistNow(): Map<String, Manifest.F> = finalCloud
    private fun hashNow(rel: String) = finalCloud[rel]?.hash

    private fun updateMirrorAndFingerprints(pl: Plan? = null) {
        val srcs = listOfNotNull(lastUpSrc, mergeDir.takeIf { it.isDirectory })
        syncMirror(srcs)
        state.notes = HashMap(NotesMerger.fingerprints(mirror, File(tmp, "fp")))
        state.save()
        phase("mirror updated (${state.notes.size} notes)")
    }

    private val mergeDir get() = File(tmp, "merge")

    private fun merge() {
        Log.i("[${p.name}] plan: per-note merge with cloud rev ${manifest.rev} (last synced rev ${state.rev})")
        phase("merge: refreshing cloud mirror")
        syncMirrorFromListing()
        val w = mergeDir
        local.readTo(lscan.keys, w)
        phase("merge: copied local data")
        // Safety net: keep this device's pre-merge data.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val broot = File(App.ctx.filesDir, "backups/${p.id}")
        w.copyRecursively(File(broot, "$stamp-local-premerge"), true)
        broot.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.deleteRecursively() }

        val prefixes = listOf(p.localPath.trimEnd('/') + "/", "/data/user/0/${p.pkg}/", "/data/data/${p.pkg}/").distinct()
        val out = NotesMerger(w, mirror, state.notes, File(tmp, "mw"), prefixes).merge()
        Log.i("[${p.name}] merge: ${out.fromCloud} note(s) from cloud, ${out.fromLocal} from this device, ${out.conflicts} edited on both (newer kept), ${out.deleted} deleted")
        phase("merge: databases merged")

        val merged = HashMap<String, File>()
        w.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(w).invariantSeparatorsPath
            if (filter.accept(rel)) merged[rel] = f
        }
        val mh = merged.mapValues { LocalFs.sha256(it.value) }

        val writes = merged.filter { (rel, _) -> mh[rel] != lscan[rel]?.hash }
        val delLocal = lscan.keys - merged.keys
        val uploads = merged.filter { (rel, _) -> mh[rel] != rhash[rel] }
        val delRemote = rlist.keys - merged.keys

        val newTags = HashMap<String, RStat>()
        parallel(uploads.entries) { (rel, f) ->
            remote.upload(rel, f)
            val st = remote.stat(rel) ?: RStat(f.length(), "")
            synchronized(this) { res.bytes += f.length(); newTags[rel] = st }
        }
        delRemote.forEach { remote.delete(it) }
        guardApp { local.apply(writes, delLocal, if (writes.isNotEmpty() || delLocal.isNotEmpty()) p.clearAfterPull else emptyList()) }
        phase("merge: applied")

        res.up = uploads.size; res.down = writes.size; res.delRemote = delRemote.size; res.delLocal = delLocal.size
        res.conflicts = out.conflicts
        res.note = "merged: ${out.fromCloud} from cloud, ${out.fromLocal} local"
        if (out.conflicts > 0) App.notify(p.id.hashCode(), "Notes edited on two devices",
            "${out.conflicts} note(s) were changed on both devices; the newer edit was kept. A backup of this device's notes was saved.")

        // Record the agreed state.
        val entries = HashMap<String, SyncState.Entry>()
        val mf = Manifest(manifest.rev + 1, App.deviceName, System.currentTimeMillis())
        for ((rel, h) in mh) {
            val st = newTags[rel] ?: rlist[rel] ?: continue
            mf.files[rel] = Manifest.F(h, st.size, st.tag)
            entries[rel] = SyncState.Entry(h, st.size, st.tag, -1, -1)
        }
        if (uploads.isNotEmpty() || delRemote.isNotEmpty()) remote.writeMeta("manifest.json", mf.bytes()) else mf.rev = manifest.rev
        finalCloud = mf.files
        state.entries = entries; state.rev = mf.rev; state.save()
    }

    /** Before a merge the mirror must match the cloud as listed now. */
    private fun syncMirrorFromListing() {
        finalCloud = rlist.mapValues { (rel, st) -> Manifest.F(rhash[rel]!!, st.size, st.tag) }
        syncMirror()
    }

    /**
     * The app may have been reopened while we were transferring. Never write under a running
     * app: stop it right before installing, and reopen it if the user was looking at it.
     */
    private fun guardApp(install: () -> Unit) {
        val guard = p.root && p.pkg.isNotBlank() && p.mode != Profile.MODE_FOLDER
        val wasFg = guard && Root.isForeground(p.pkg)
        if (guard) Root.forceStop(p.pkg)
        install()
        if (wasFg) { Log.i("[${p.name}] app was reopened during sync → restarted with new data"); Root.launch(p.pkg) }
    }

    /** Keep a copy of the losing side of a snapshot conflict in app storage (last 5 kept). */
    private fun backup(pl: Plan) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val root = File(App.ctx.filesDir, "backups/${p.id}")
        if (pl.snapshotDir == Dir.PULL) {
            val d = File(root, "$stamp-local"); local.readTo(lscan.keys, d)
            Log.i("[${p.name}] backed up local copy to ${d.path}")
        } else {
            val d = File(root, "$stamp-cloud")
            rlist.keys.forEach { rel ->
                val f = downloaded[rel]
                if (f != null) f.copyTo(File(d, rel), true) else remote.download(rel, File(d, rel))
            }
            Log.i("[${p.name}] backed up cloud copy to ${d.path}")
        }
        root.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.deleteRecursively() }
    }

    /** Record the agreed state and publish the manifest. */
    private fun saveState(newTags: Map<String, RStat>, pl: Plan? = null) {
        val entries = HashMap<String, SyncState.Entry>()
        val mf = Manifest(manifest.rev, manifest.device, manifest.time)
        val remoteNow = HashMap(rlist)
        pl?.delRemote?.forEach { remoteNow.remove(it) }
        newTags.forEach { (k, v) -> remoteNow[k] = v }
        val localDeleted = pl?.delLocal ?: emptySet()

        for ((rel, st) in remoteNow) {
            val h = when {
                pl != null && rel in pl.upload -> lscan[pl.conflictCopies[rel] ?: rel]?.hash
                else -> rhash[rel]
            } ?: continue
            mf.files[rel] = Manifest.F(h, st.size, st.tag)
            val l = lscan[rel]
            val localHasIt = when {
                pl != null && (rel in pl.download || rel in pl.conflictCopies) -> true
                rel in localDeleted -> false
                else -> l?.hash == h
            }
            if (!localHasIt) continue
            // Local stat: unchanged files keep their scan stat; freshly written ones get rehashed next time.
            val (ls, lm) = if (l != null && l.hash == h) l.size to l.mtime else -1L to -1L
            entries[rel] = SyncState.Entry(h, st.size, st.tag, ls, lm)
        }

        val remoteChanged = pl != null && (pl.upload.isNotEmpty() || pl.delRemote.isNotEmpty())
        val manifestStale = mf.files != manifest.files
        if (remoteChanged || manifestStale) {
            if (remoteChanged) { mf.rev = manifest.rev + 1; mf.device = App.deviceName; mf.time = System.currentTimeMillis() }
            remote.writeMeta("manifest.json", mf.bytes())
        }
        state.entries = entries
        state.rev = mf.rev
        state.save()
        finalCloud = mf.files
    }

    companion object {
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
        val running: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Runs a sync for [p], recording the outcome. Never throws. */
        fun sync(p: Profile, force: Boolean = false, why: String = "manual"): SyncResult? {
            val lock = locks.getOrPut(p.id) { ReentrantLock() }
            if (!lock.tryLock()) { Log.i("[${p.name}] already syncing, skipped ($why)"); return null }
            running += p.id
            val t0 = System.currentTimeMillis()
            try {
                val r = SyncEngine(p, force).run()
                val st = SyncState.load(p.id)
                st.lastTime = System.currentTimeMillis(); st.lastResult = r.toString(); st.lastOk = true; st.save()
                Log.i("[${p.name}] $why sync: $r in ${System.currentTimeMillis() - t0} ms")
                return r
            } catch (e: Exception) {
                Log.e("[${p.name}] $why sync failed", e)
                val st = SyncState.load(p.id)
                st.lastTime = System.currentTimeMillis(); st.lastResult = "Failed: ${e.message}"; st.lastOk = false; st.save()
                return null
            } finally {
                running -= p.id
                lock.unlock()
            }
        }

        /** Cheap check (one small request): has another device pushed since our last sync? */
        fun remoteHasNews(p: Profile): Boolean {
            val acc = Store.account(p.accountId) ?: return false
            val m = Manifest.read(Remote.of(acc, p.remotePath))
            return m.exists && m.rev != SyncState.load(p.id).rev
        }
    }
}
