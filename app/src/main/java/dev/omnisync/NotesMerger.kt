package dev.omnisync

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Note-level three-way merge for Samsung Notes.
 *
 * A note = its rows in every note-scoped table (keyed by the note UUID) + its files
 * (SDocData/<name>.sdocx/, app_attach/<UUID>/, and any file a row points to).
 * For every note we compare this device, the cloud, and the fingerprint both had at
 * the last sync; whichever side changed wins, and if both changed the newer edit wins.
 * Folders (category_tree) and hashtags are merged by union.
 *
 * [local] is a writable copy of this device's data (it becomes the merged result),
 * [cloud] is a read-only mirror of the cloud copy.
 */
class NotesMerger(
    private val local: File,
    private val cloud: File,
    private val base: Map<String, String>,
    private val work: File,
    private val dataPrefixes: List<String>,
) {
    data class Outcome(val fromCloud: Int, val fromLocal: Int, val conflicts: Int, val deleted: Int)

    companion object {
        const val DB = "databases/sdoc.db"

        /** table -> column holding the note UUID */
        val NOTE_TABLES = listOf(
            "sdoc" to "UUID", "content" to "sdocUUID", "tag_list" to "docUUID", "retry" to "sdocUUID",
            "stroke" to "sdocUUID", "document_page" to "documentUUID", "hashtagContent" to "sdocUUID",
            "text_search" to "sdocUUID", "document_thumbnail" to "UUID", "page_search_info" to "sdocUUID",
            "suggested_cover" to "documentUUID", "document_cover" to "documentUUID",
            "organized_document" to "documentUUID", "stt_content" to "documentUUID",
            "document_temp_data" to "UUID", "sync_delay_info" to "UUID", "document_extra" to "UUID",
            "external_shared_documents" to "UUID", "mapped_document" to "UUID",
        )
        val PATH_COLUMNS = setOf("filePath", "_data", "imagePath", "strokeFilePath", "absolutePath")

        /** What counts as "this note changed" (deliberately ignores open counters, dirty flags, ids). */
        private const val FP_SQL = "SELECT UUID, lastModifiedAt||'|'||isDeleted||'|'||categoryUUID||'|'||isFavorite||'|'||isLock||'|'||recycle_bin_time_moved||'|'||title||'|'||size||'|'||backgroundColor FROM %s.sdoc"

        /** Opens a db copy (applying any WAL) and rewrites it as one self-contained file. */
        fun normalize(dbFile: File): Boolean {
            if (!dbFile.exists()) return false
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            }
            File(dbFile.path + "-wal").delete(); File(dbFile.path + "-shm").delete(); File(dbFile.path + "-journal").delete()
            return true
        }

        /** Per-note fingerprints of a data folder (copies the db so the source is never modified). */
        fun fingerprints(dataDir: File, work: File): Map<String, String> {
            val src = File(dataDir, DB)
            if (!src.exists()) return emptyMap()
            val d = File(work, "fp-${System.nanoTime()}"); d.mkdirs()
            try {
                val db = File(d, "sdoc.db"); src.copyTo(db)
                File(src.path + "-wal").takeIf { it.exists() }?.copyTo(File(db.path + "-wal"))
                normalize(db)
                SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { x ->
                    return readFp(x, "main")
                }
            } finally { d.deleteRecursively() }
        }

        private fun readFp(db: SQLiteDatabase, schema: String): Map<String, String> {
            val m = HashMap<String, String>()
            db.rawQuery(FP_SQL.format(schema), null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getString(1) ?: "" }
            return m
        }
    }

    fun merge(): Outcome {
        // Cloud db: private normalized copy, attached read-only.
        val rdb = File(work, "cloud-sdoc.db"); work.mkdirs()
        File(cloud, DB).copyTo(rdb, true)
        File(cloud, "$DB-wal").takeIf { it.exists() }?.copyTo(File(rdb.path + "-wal"), true)
        normalize(rdb)
        val ldbFile = File(local, DB)
        normalize(ldbFile)

        var fromCloud = 0; var fromLocal = 0; var conflicts = 0; var deleted = 0
        SQLiteDatabase.openDatabase(ldbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("ATTACH DATABASE ? AS r", arrayOf(rdb.path))
            val lfp = readFp(db, "main"); val rfp = readFp(db, "r")
            val ltime = times(db, "main"); val rtime = times(db, "r")
            val tablesMain = tables(db, "main"); val tablesR = tables(db, "r")

            val takeCloud = ArrayList<String>()   // notes whose cloud version replaces ours
            for (u in (lfp.keys + rfp.keys)) {
                val l = lfp[u]; val r = rfp[u]; val b = base[u]
                if (l == r) continue
                when {
                    l == b -> takeCloud += u                  // only the cloud changed (or deleted) it
                    r == b -> fromLocal++                     // only we changed it: keep ours
                    else -> {                                 // both changed: newer edit wins
                        conflicts++
                        if ((rtime[u] ?: 0) > (ltime[u] ?: 0) || l == null) takeCloud += u else fromLocal++
                    }
                }
            }

            db.beginTransaction()
            try {
                for (u in takeCloud) {
                    val oldFiles = noteFiles(db, "main", u, tablesMain)
                    val newFiles = noteFiles(db, "r", u, tablesR)
                    deleteNoteRows(db, u, tablesMain)
                    if (rfp.containsKey(u)) { copyNoteRows(db, u, tablesMain, tablesR); fromCloud++ } else deleted++
                    oldFiles.forEach { File(local, it).deleteRecursively() }
                    newFiles.forEach { rel ->
                        val s = File(cloud, rel)
                        if (s.isDirectory) s.copyRecursively(File(local, rel), true)
                        else if (s.isFile) s.copyTo(File(local, rel), true)
                    }
                }
                mergeGlobals(db, tablesMain, tablesR)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            db.execSQL("DETACH DATABASE r")
        }
        rdb.delete()
        File(ldbFile.path + "-wal").delete(); File(ldbFile.path + "-shm").delete()
        return Outcome(fromCloud, fromLocal, conflicts, deleted)
    }

    private fun times(db: SQLiteDatabase, s: String): Map<String, Long> {
        val m = HashMap<String, Long>()
        db.rawQuery("SELECT UUID, lastModifiedAt FROM $s.sdoc", null).use { c -> while (c.moveToNext()) m[c.getString(0)] = c.getLong(1) }
        return m
    }

    private fun tables(db: SQLiteDatabase, s: String): Set<String> {
        val t = HashSet<String>()
        db.rawQuery("SELECT name FROM $s.sqlite_master WHERE type='table'", null).use { c -> while (c.moveToNext()) t += c.getString(0) }
        return t
    }

    private fun columns(db: SQLiteDatabase, s: String, table: String): List<String> {
        val cols = ArrayList<String>()
        db.rawQuery("PRAGMA $s.table_info(`$table`)", null).use { c -> while (c.moveToNext()) cols += c.getString(1) }
        return cols
    }

    /** Columns to copy: shared by both schemas, minus the autoincrement row id (ids differ per device). */
    private fun copyCols(db: SQLiteDatabase, table: String) =
        columns(db, "main", table).intersect(columns(db, "r", table).toSet()).filter { it != "_id" }
            .joinToString(",") { "`$it`" }

    private fun deleteNoteRows(db: SQLiteDatabase, u: String, tm: Set<String>) {
        if ("sticky" in tm) {
            for (t in listOf("sticky_text", "sticky_stroke")) if (t in tm)
                db.execSQL("DELETE FROM main.$t WHERE objectId IN (SELECT objectId FROM main.sticky WHERE documentUUID=?)", arrayOf(u))
            db.execSQL("DELETE FROM main.sticky WHERE documentUUID=?", arrayOf(u))
        }
        for ((t, c) in NOTE_TABLES) if (t in tm) db.execSQL("DELETE FROM main.`$t` WHERE `$c`=?", arrayOf(u))
    }

    private fun copyNoteRows(db: SQLiteDatabase, u: String, tm: Set<String>, tr: Set<String>) {
        for ((t, c) in NOTE_TABLES) if (t in tm && t in tr) {
            val cols = copyCols(db, t)
            db.execSQL("INSERT OR REPLACE INTO main.`$t` ($cols) SELECT $cols FROM r.`$t` WHERE `$c`=?", arrayOf(u))
        }
        if ("sticky" in tm && "sticky" in tr) {
            val cols = copyCols(db, "sticky")
            db.execSQL("INSERT OR REPLACE INTO main.sticky ($cols) SELECT $cols FROM r.sticky WHERE documentUUID=?", arrayOf(u))
            for (t in listOf("sticky_text", "sticky_stroke")) if (t in tm && t in tr) {
                val cc = copyCols(db, t)
                db.execSQL("INSERT OR REPLACE INTO main.$t ($cc) SELECT $cc FROM r.$t WHERE objectId IN (SELECT objectId FROM r.sticky WHERE documentUUID=?)", arrayOf(u))
            }
        }
    }

    /** Relative paths (dirs or files) belonging to note [u] according to schema [s]. */
    private fun noteFiles(db: SQLiteDatabase, s: String, u: String, ts: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        out += "app_attach/$u"
        for ((t, c) in NOTE_TABLES) if (t in ts) {
            val pcs = columns(db, s, t).filter { it in PATH_COLUMNS }
            if (pcs.isEmpty()) continue
            db.rawQuery("SELECT ${pcs.joinToString(",") { "`$it`" }} FROM $s.`$t` WHERE `$c`=?", arrayOf(u)).use { cur ->
                while (cur.moveToNext()) for (i in pcs.indices) cur.getString(i)?.let { toRel(it) }?.let { out += it }
            }
        }
        return out.filter { it.isNotBlank() && !it.startsWith("databases") }.toSet()
    }

    private fun toRel(path: String): String? {
        for (p in dataPrefixes) if (path.startsWith(p)) return path.removePrefix(p).trim('/')
        return null
    }

    private fun mergeGlobals(db: SQLiteDatabase, tm: Set<String>, tr: Set<String>) {
        if ("category_tree" in tm && "category_tree" in tr) {
            val cols = copyCols(db, "category_tree")
            db.execSQL("""INSERT OR REPLACE INTO main.category_tree ($cols) SELECT $cols FROM r.category_tree rc
                WHERE NOT EXISTS (SELECT 1 FROM main.category_tree m WHERE m.UUID = rc.UUID AND m.lastModifiedAt >= rc.lastModifiedAt)""")
        }
        if ("category_tree_closure" in tm && "category_tree_closure" in tr) {
            val cols = copyCols(db, "category_tree_closure")
            db.execSQL("INSERT OR IGNORE INTO main.category_tree_closure ($cols) SELECT $cols FROM r.category_tree_closure")
        }
        if ("hashtag" in tm && "hashtag" in tr) {
            val cols = copyCols(db, "hashtag")
            db.execSQL("INSERT INTO main.hashtag ($cols) SELECT $cols FROM r.hashtag WHERE UUID NOT IN (SELECT UUID FROM main.hashtag)")
        }
    }
}
