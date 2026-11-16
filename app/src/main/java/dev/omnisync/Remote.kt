package dev.omnisync

import android.util.Xml
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Size + opaque change tag (ETag or mtime) of a remote file. */
data class RStat(val size: Long, val tag: String)

/** A storage location in the cloud, rooted at the profile's remote folder. Paths are '/'-separated and relative. */
interface Remote {
    fun list(): Map<String, RStat>
    fun stat(rel: String): RStat?
    fun download(rel: String, dest: File)
    fun upload(rel: String, src: File)
    fun delete(rel: String)
    fun readMeta(name: String): ByteArray?
    fun writeMeta(name: String, data: ByteArray)
    fun deleteMeta(name: String)

    companion object {
        const val META_DIR = ".omnisync"

        fun of(a: Account, remotePath: String): Remote = when (a.type) {
            Account.TYPE_LOCAL -> FolderRemote(File(a.url, remotePath.trim('/')))
            else -> WebDavRemote(a.url, a.user, a.pass, remotePath)
        }
    }
}

class RemoteException(msg: String) : IOException(msg)

class WebDavRemote(baseUrl: String, user: String, pass: String, remotePath: String) : Remote {
    private val base: HttpUrl = run {
        var b = baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
        remotePath.split('/').filter { it.isNotBlank() }.forEach { b = b.addPathSegment(it) }
        b.build()
    }
    private val basePath = base.encodedPath.trimEnd('/').let(::decode)
    private val auth = if (user.isNotEmpty()) Credentials.basic(user, pass) else null
    private val http = client

    private val madeDirs: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun url(rel: String, dir: Boolean = false): HttpUrl {
        val b = base.newBuilder()
        rel.split('/').filter { it.isNotEmpty() }.forEach { b.addPathSegment(it) }
        if (dir) b.addPathSegment("")
        return b.build()
    }

    private fun req(u: HttpUrl) = Request.Builder().url(u).apply { auth?.let { header("Authorization", it) } }

    private fun exec(r: Request): Response = http.newCall(r).execute()

    private fun fail(what: String, r: Response): Nothing {
        val code = r.code; r.close()
        throw RemoteException("$what failed: HTTP $code" + when (code) {
            401 -> " (wrong user/password)"; 403 -> " (forbidden)"; 507 -> " (cloud storage full)"; else -> ""
        })
    }

    private data class Prop(val path: String, val isDir: Boolean, val size: Long, val tag: String)

    private fun propfind(u: HttpUrl, depth: String): List<Prop>? {
        val body = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>"""
        val r = exec(req(u).header("Depth", depth)
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaTypeOrNull())).build())
        if (r.code == 404) { r.close(); return null }
        if (r.code != 207 && !r.isSuccessful) fail("PROPFIND ${decode(u.encodedPath)}", r)
        return r.body!!.byteStream().use { parse(it) }.also { r.close() }
    }

    private fun parse(ins: java.io.InputStream): List<Prop> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(ins, null)
        val res = ArrayList<Prop>()
        var href = ""; var dir = false; var len = -1L; var etag = ""; var lm = ""; var text = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when (p.name) {
                        "response" -> { href = ""; dir = false; len = -1; etag = ""; lm = "" }
                        "collection" -> dir = true
                    }
                }
                XmlPullParser.TEXT -> text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> len = text.toString().trim().toLongOrNull() ?: -1
                    "getetag" -> etag = text.toString().trim().removePrefix("W/").trim('"')
                    "getlastmodified" -> lm = text.toString().trim()
                    "response" -> {
                        val path = if (href.startsWith("http")) href.toHttpUrl().encodedPath else href
                        res.add(Prop(decode(path).trimEnd('/'), dir, len, etag.ifEmpty { lm }))
                    }
                }
            }
            ev = p.next()
        }
        return res
    }

    private fun relOf(path: String) = path.removePrefix(basePath).trim('/')

    override fun list(): Map<String, RStat> {
        val out = HashMap<String, RStat>()
        val queue = ArrayDeque(listOf(""))
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val props = propfind(url(dir, true), "1") ?: continue
            if (dir.isEmpty()) madeDirs.add("")
            for (pr in props) {
                val rel = relOf(pr.path)
                if (rel == dir) continue
                if (rel == Remote.META_DIR || rel.startsWith(Remote.META_DIR + "/")) continue
                if (pr.isDir) { madeDirs.add(rel); queue.add(rel) } else out[rel] = RStat(pr.size, pr.tag)
            }
        }
        return out
    }

    override fun stat(rel: String): RStat? =
        propfind(url(rel), "0")?.firstOrNull()?.let { RStat(it.size, it.tag) }

    @Synchronized private fun mkdirs(relDir: String) {
        if (relDir.isEmpty()) {
            if ("" in madeDirs) return
            // Base folder: create missing segments; intermediate segments may be
            // server-owned (e.g. /remote.php/dav) so only the last one must succeed.
            if (propfind(base.newBuilder().addPathSegment("").build(), "0") == null) {
                val segs = base.pathSegments.filter { it.isNotEmpty() }
                var b = base.newBuilder().encodedPath("/")
                segs.forEachIndexed { i, s ->
                    b = b.addPathSegment(s)
                    val u = b.build().newBuilder().addPathSegment("").build()
                    if (i == segs.lastIndex) mkcol(u)
                    else exec(req(u).method("MKCOL", null).build()).close()
                }
            }
            madeDirs.add(""); return
        }
        if (relDir in madeDirs) return
        mkdirs(relDir.substringBeforeLast('/', ""))
        mkcol(url(relDir, true)); madeDirs.add(relDir)
    }

    private fun mkcol(u: HttpUrl) {
        val r = exec(req(u).method("MKCOL", null).build())
        // 201 created, 405 already exists (some servers 301/409 when exists)
        if (r.isSuccessful || r.code == 405 || r.code == 301) { r.close(); return }
        fail("MKCOL ${decode(u.encodedPath)}", r)
    }

    override fun download(rel: String, dest: File) {
        val r = exec(req(url(rel)).get().build())
        if (!r.isSuccessful) fail("GET $rel", r)
        dest.parentFile?.mkdirs()
        r.body!!.byteStream().use { i -> dest.outputStream().use { i.copyTo(it) } }
        r.close()
    }

    override fun upload(rel: String, src: File) {
        mkdirs(rel.substringBeforeLast('/', ""))
        val r = exec(req(url(rel)).put(src.asRequestBody("application/octet-stream".toMediaTypeOrNull())).build())
        if (!r.isSuccessful) fail("PUT $rel", r)
        r.close()
    }

    override fun delete(rel: String) {
        val r = exec(req(url(rel)).delete().build())
        if (!r.isSuccessful && r.code != 404) fail("DELETE $rel", r)
        r.close()
    }

    override fun readMeta(name: String): ByteArray? {
        val r = exec(req(url("${Remote.META_DIR}/$name")).get().build())
        if (r.code == 404) { r.close(); return null }
        if (!r.isSuccessful) fail("GET meta $name", r)
        return r.body!!.bytes().also { r.close() }
    }

    override fun writeMeta(name: String, data: ByteArray) {
        mkdirs(Remote.META_DIR)
        val r = exec(req(url("${Remote.META_DIR}/$name")).put(data.toRequestBody("application/json".toMediaTypeOrNull())).build())
        if (!r.isSuccessful) fail("PUT meta $name", r)
        r.close()
    }

    override fun deleteMeta(name: String) = delete("${Remote.META_DIR}/$name")

    companion object {
        private fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

/** "Cloud" that is just a folder: an SD card, a USB drive, or a folder another app (FolderSync, Syncthing, Drive client…) uploads. */
class FolderRemote(private val dir: File) : Remote {
    private fun f(rel: String) = File(dir, rel)
    private val meta get() = File(dir, Remote.META_DIR)

    override fun list(): Map<String, RStat> {
        if (!dir.isDirectory) return emptyMap()
        return dir.walkTopDown().onEnter { it != meta }.filter { it.isFile }.associate {
            it.relativeTo(dir).invariantSeparatorsPath to RStat(it.length(), it.lastModified().toString())
        }
    }

    override fun stat(rel: String) = f(rel).takeIf { it.isFile }?.let { RStat(it.length(), it.lastModified().toString()) }

    override fun download(rel: String, dest: File) { dest.parentFile?.mkdirs(); f(rel).copyTo(dest, true) }

    override fun upload(rel: String, src: File) {
        val t = f("$rel.omnitmp"); t.parentFile?.mkdirs()
        src.copyTo(t, true)
        if (!t.renameTo(f(rel))) { f(rel).delete(); if (!t.renameTo(f(rel))) throw IOException("rename failed: $rel") }
    }

    override fun delete(rel: String) {
        f(rel).delete()
        var p = f(rel).parentFile
        while (p != null && p != dir && p.list()?.isEmpty() == true) { p.delete(); p = p.parentFile }
    }

    override fun readMeta(name: String) = File(meta, name).takeIf { it.isFile }?.readBytes()
    override fun writeMeta(name: String, data: ByteArray) { meta.mkdirs(); File(meta, name).writeBytes(data) }
    override fun deleteMeta(name: String) { File(meta, name).delete() }
}
