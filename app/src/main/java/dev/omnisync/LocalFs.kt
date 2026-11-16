package dev.omnisync

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A local file as seen during a scan. mtime in ms. */
data class LEntry(val size: Long, val mtime: Long, val hash: String)

/** Include/exclude glob filter. `*` = within a segment, `**` = any depth, a bare dir name includes its contents. */
class PathFilter(includes: List<String>, excludes: List<String>) {
    private val inc = includes.filter { it.isNotBlank() }.map(::rx)
    private val exc = excludes.filter { it.isNotBlank() }.map(::rx)

    fun accept(rel: String): Boolean {
        if (rel == Remote.META_DIR || rel.startsWith(Remote.META_DIR + "/")) return false
        if (rel.endsWith(".omnitmp")) return false
        if (inc.isNotEmpty() && inc.none { it.matches(rel) }) return false
        return exc.none { it.matches(rel) }
    }

    companion object {
        fun rx(glob: String): Regex {
            val g = glob.trim().trim('/')
            val sb = StringBuilder()
            var i = 0
            while (i < g.length) {
                val c = g[i]
                when {
                    g.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3; continue }
                    g.startsWith("**", i) -> { sb.append(".*"); i += 2; continue }
                    c == '*' -> sb.append("[^/]*")
                    c == '?' -> sb.append("[^/]")
                    else -> sb.append(Regex.escape(c.toString()))
                }
                i++
            }
            // A pattern also matches everything below it (so "SDocData" == "SDocData/**").
            return Regex("$sb(?:/.*)?")
        }
    }
}

interface LocalFs {
    /** Lists and hashes accepted files. [known] lets cheap backends skip re-hashing unchanged files. */
    fun scan(filter: PathFilter, known: Map<String, SyncState.Entry>): Map<String, LEntry>
    /** Copies the given local files to dest/rel (so they can be uploaded). */
    fun readTo(rels: Collection<String>, dest: File)
    /** Atomically-ish installs downloaded files and removes deleted ones. */
    fun apply(writes: Map<String, File>, deletes: Collection<String>, clear: List<String>)
    fun exists(): Boolean

    companion object {
        fun of(p: Profile): LocalFs = if (p.root) RootFs(p.localPath) else PlainFs(File(p.localPath))

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class PlainFs(private val dir: File) : LocalFs {
    override fun exists() = dir.isDirectory

    override fun scan(filter: PathFilter, known: Map<String, SyncState.Entry>): Map<String, LEntry> {
        if (!dir.isDirectory) return emptyMap()
        val out = HashMap<String, LEntry>()
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (!filter.accept(rel)) return@forEach
            val size = f.length(); val mt = f.lastModified()
            val k = known[rel]
            val hash = if (k != null && k.lsize == size && k.lmtime == mt) k.hash else LocalFs.sha256(f)
            out[rel] = LEntry(size, mt, hash)
        }
        return out
    }

    override fun readTo(rels: Collection<String>, dest: File) {
        rels.forEach { File(dir, it).copyTo(File(dest, it), true) }
    }

    override fun apply(writes: Map<String, File>, deletes: Collection<String>, clear: List<String>) {
        for ((rel, src) in writes) {
            val target = File(dir, rel); target.parentFile?.mkdirs()
            val tmp = File(dir, "$rel.omnitmp")
            src.copyTo(tmp, true)
            if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw IOException("Cannot write $rel") }
        }
        deletes.forEach { File(dir, it).delete() }
        clear.forEach { c -> File(dir, c).let { if (it.isDirectory) it.listFiles()?.forEach { x -> x.deleteRecursively() } else it.delete() } }
    }
}

/**
 * Root mode: reads/writes any path (e.g. another app's /data/data) through su, then restores
 * the owner uid/gid and SELinux label of the target folder so the owning app can use the files.
 */
class RootFs(private val path: String) : LocalFs {
    private val q = Root::q
    private val work get() = File(App.ctx.cacheDir, "root").apply { mkdirs() }

    private fun check(r: Root.Result, what: String): Root.Result {
        if (!r.ok) throw IOException("$what (root) failed [${r.code}]: ${r.err.trim().take(300)}")
        return r
    }

    override fun exists(): Boolean {
        val r = Root.run("[ -d ${q(path)} ]")
        if (r.code != 0 && r.code != 1) throw IOException("Root shell failed [${r.code}]: ${r.err.trim().take(200)} — is root granted to OmniSync?")
        return r.ok
    }

    override fun scan(filter: PathFilter, known: Map<String, SyncState.Entry>): Map<String, LEntry> {
        if (!Root.available()) throw IOException("Root access not granted")
        val r = check(Root.run("cd ${q(path)} || exit 3\nfind . -type f -exec stat -c '%s|%Y|%n' {} + 2>/dev/null; true"), "list $path")
        val stats = HashMap<String, Pair<Long, Long>>()
        r.out.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val a = line.split('|', limit = 3)
            if (a.size < 3) return@forEach
            val rel = a[2].removePrefix("./")
            if (filter.accept(rel)) stats[rel] = a[0].toLong() to a[1].toLong() * 1000
        }
        if (stats.isEmpty()) return emptyMap()
        // Hash everything in a single root call (sha256sum is native and fast).
        val list = File(work, "hashlist.txt"); list.writeText(stats.keys.joinToString("\n", postfix = "\n"))
        val h = check(Root.run("cd ${q(path)} || exit 3\ntr '\\n' '\\0' < ${q(list.path)} | xargs -0 sha256sum"), "hash")
        list.delete()
        val hashes = HashMap<String, String>()
        h.out.lineSequence().filter { it.length > 66 }.forEach { hashes[it.substring(66).removePrefix("./")] = it.substring(0, 64) }
        return stats.mapNotNull { (rel, st) -> hashes[rel]?.let { rel to LEntry(st.first, st.second, it) } }.toMap()
    }

    override fun readTo(rels: Collection<String>, dest: File) {
        if (rels.isEmpty()) return
        dest.mkdirs()
        val uid = android.os.Process.myUid()
        val list = File(work, "readlist.txt"); list.writeText(rels.joinToString("\n", postfix = "\n"))
        // One tar pipe instead of a process per file (forks are slow under su).
        check(Root.run("cd ${q(path)} || exit 3\nset -e\ntar -cf - -T ${q(list.path)} | tar -xf - -C ${q(dest.path)}\n" +
            "chown -R $uid:$uid ${q(dest.path)}\nchcon -R \"\$(stat -c %C ${q(App.ctx.cacheDir.path)})\" ${q(dest.path)}"), "read files")
        list.delete()
    }

    override fun apply(writes: Map<String, File>, deletes: Collection<String>, clear: List<String>) {
        val sb = StringBuilder("cd ${q(path)} || exit 3\nowner=\$(stat -c %u:%g .)\nctx=\$(stat -c %C .)\nmode=\$(stat -c %a .)\n")
        val tops = LinkedHashSet<String>()
        // Stage all new files in one tree, then unpack it over the target with a single tar pipe.
        val stage = File(work, "stage"); stage.deleteRecursively()
        for ((rel, src) in writes) { val t = File(stage, rel); t.parentFile?.mkdirs(); src.copyTo(t, true) }
        // Archive FILES only: a directory entry (e.g. "./") would stamp the staging dir's owner and
        // mode onto the app's real folders and lock the app out of its own data.
        val flist = File(work, "stagelist.txt"); flist.writeText(writes.keys.joinToString("\n", postfix = "\n"))
        if (writes.isNotEmpty()) sb.append("(cd ${q(stage.path)} && tar -cf - -T ${q(flist.path)}) | tar -xf - -C . || exit 4\n")
        for (rel in writes.keys) {
            tops += rel.substringBefore('/')
            // A replaced SQLite db/WAL must never be paired with a stale shared-memory index.
            if (rel.endsWith(".db") || rel.endsWith("-wal")) sb.append("rm -f ${q(rel.removeSuffix("-wal") + "-shm")}\n")
        }
        for (rel in deletes) {
            sb.append("rm -f ${q(rel)}\n")
            tops += rel.substringBefore('/')
        }
        for (c in clear) sb.append("if [ -d ${q(c)} ]; then find ${q(c)} -mindepth 1 -delete; else rm -f ${q(c)}; fi\n")
        for (t in tops) {
            sb.append("if [ -d ${q(t)} ]; then find ${q(t)} -mindepth 1 -type d -empty -delete 2>/dev/null; fi\n")
            sb.append("[ -e ${q(t)} ] && chown -R \"\$owner\" ${q(t)} && chcon -R \"\$ctx\" ${q(t)}\n")
        }
        // Belt and braces: the data folder itself always keeps its original owner and mode.
        sb.append("chown \"\$owner\" . ; chmod \"\$mode\" .\n")
        sb.append("true\n")
        try { check(Root.run(sb.toString()), "install files") } finally { stage.deleteRecursively(); flist.delete() }
    }
}
