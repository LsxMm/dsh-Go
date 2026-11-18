package io.github.social.dsh_app.phone

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 解除限制 › 完整 root: one shell command as uid 0, run by the app process itself through `su`.
 *
 * This is deliberately NOT the privileged display channel: that protocol only speaks its fixed
 * verb set and must keep doing so. `su -c` is the same route every root app on the device uses;
 * KernelSU / Magisk answer it with their own authorization UI the first time.
 *
 * Only reachable while the person turned the 完整 root rule on ([PhoneUnlocks.rootShell]);
 * [PhoneCore] checks that before calling. Output is capped so one command cannot flood the model.
 */
class RootShell(private val spawn: (List<String>) -> Process = { ProcessBuilder(it).start() }) {

    class Result(
        val ok: Boolean,
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        val started: Boolean = true,
    )

    fun run(command: String, cwd: String?, timeoutMs: Long): Result {
        val shell = command.trim()
        if (shell.isEmpty()) return Result(false, -1, "", "命令为空")
        val line = if (cwd.isNullOrBlank()) shell else "cd " + quote(cwd.trim()) + " && " + shell
        var process: Process? = null
        var lastError: Exception? = null
        // Bare `su` first (right for Magisk / APatch), then the absolute paths a rooted manager uses.
        for (binary in BINARIES) {
            process = try { spawn(listOf(binary, "-c", line)) } catch (e: Exception) { lastError = e; null }
            if (process != null) break
        }
        val child = process ?: return Result(
            false, -1, "",
            "su 启动失败：" + (lastError?.javaClass?.simpleName ?: IOException::class.java.simpleName),
        )
        val out = Pump(child.inputStream)
        val err = Pump(child.errorStream)
        out.start(); err.start()
        val finished = try {
            child.waitFor(timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            child.destroyForcibly(); out.join(); err.join()
            Thread.currentThread().interrupt()
            return Result(false, -1, out.text(), err.text(), timedOut = true)
        }
        if (!finished) {
            child.destroyForcibly()
            out.join(); err.join()
            return Result(false, -1, out.text(), err.text(), timedOut = true)
        }
        out.join(); err.join()
        val code = child.exitValue()
        return Result(code == 0, code, out.text(), err.text())
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Reads one stream on its own thread, keeping at most [MAX_BYTES] and dropping the rest. */
    private class Pump(private val stream: InputStream) {
        private val buffer = ByteArrayOutputStream()
        private var dropped = 0
        private val thread = Thread({ drain() }, "dsh-root-shell").apply { isDaemon = true }

        fun start() = thread.start()
        fun join() = runCatching { thread.join(2_000) }

        private fun drain() {
            val chunk = ByteArray(8_192)
            try {
                while (true) {
                    val n = stream.read(chunk)
                    if (n < 0) break
                    val room = MAX_BYTES - buffer.size()
                    if (room <= 0) { dropped += n; continue }
                    if (n <= room) buffer.write(chunk, 0, n) else {
                        buffer.write(chunk, 0, room); dropped += n - room
                    }
                }
            } catch (_: IOException) {
                // The child died or the pipe was closed after a timeout: keep what was read.
            }
        }

        fun text(): String = runCatching { buffer.toString(Charsets.UTF_8.name()) }.getOrDefault("") +
            if (dropped > 0) "\n…（省略 $dropped 字节）" else ""
    }

    companion object {
        /**
         * Tried in order. `su` first: that is the name every rooted manager answers; the absolute
         * paths cover managers whose su is not on the app's PATH.
         */
        val BINARIES = listOf(
            "su", "/system/bin/su", "/system/xbin/su", "/debug_ramdisk/su", "/sbin/su",
            "/data/adb/ksu/bin/su", "/data/adb/magisk/su",
        )

        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 120_000L
        const val MAX_BYTES = 60_000
    }
}
