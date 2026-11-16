package dev.omnisync

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread

/** Minimal su wrapper. One process per call; scripts are fed through stdin. */
object Root {
    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    @Volatile private var cached: Boolean? = null

    fun available(refresh: Boolean = false): Boolean {
        if (!refresh) cached?.let { return it }
        val r = try { run("id") } catch (_: Exception) { Result(-1, "", "") }
        return (r.ok && r.out.contains("uid=0")).also { cached = it }
    }

    /** Shell-quote a string for use inside a root script. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Runs [script] as root (fed via stdin, so no argv length limits). */
    fun run(script: String): Result {
        // --mount-master: the global mount namespace. An app-spawned su otherwise inherits the
        // app sandbox, where other apps' /data/data folders are hidden (Android 11+).
        val p = ProcessBuilder("su", "--mount-master").start()
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val t1 = thread { p.inputStream.copyTo(out) }
        val t2 = thread { p.errorStream.copyTo(err) }
        p.outputStream.use { os -> os.write((script + "\nexit \$?\n").toByteArray()) }
        val code = p.waitFor()
        t1.join(); t2.join()
        return Result(code, out.toString(), err.toString())
    }

    /** Run and stream stdout to a consumer (used for large outputs). */
    fun stream(script: String, consumer: (InputStream) -> Unit): Int {
        val p = ProcessBuilder("su", "--mount-master", "-c", script).redirectErrorStream(false).start()
        val t = thread { p.errorStream.readBytes() }
        p.inputStream.use(consumer)
        val code = p.waitFor(); t.join()
        return code
    }

    fun forceStop(pkg: String) = run("am force-stop ${q(pkg)}")

    fun isRunning(pkg: String): Boolean = run("pidof ${q(pkg)}").out.isNotBlank()

    /** Ground truth from the activity manager (the watcher's view can be fooled by popups). */
    fun isForeground(pkg: String): Boolean {
        val r = run("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
        return r.out.contains("$pkg/")
    }

    fun launch(pkg: String) = run("monkey -p ${q(pkg)} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
}
