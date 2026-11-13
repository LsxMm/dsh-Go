package io.github.social.dsh_app.phone

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * 完整 root: the `shell` tool stays shut until the person lifts the rule, and the command it
 * runs is exactly what the model asked for — nothing is added to it.
 */
class RootShellTest {
    private var policies = PhonePolicies(confirmMode = ConfirmMode.ALWAYS)
    private val spawns = mutableListOf<List<String>>()
    private var stdout = "uid=0(root)\n"
    private var stderr = ""
    private var exit = 0
    private var hang = false

    private val core = PhoneCore(object : PhoneEnv {
        override fun enabled() = true
        override fun policies() = policies
        override fun isLocked() = false
        override fun a11yState() = A11yState.CONNECTED
        override fun backgroundUnavailable() = "unavailable"
        override fun zone() = ZoneId.of("Asia/Shanghai")
        override fun notifyFix(kind: String) {}
        override fun sleep(ms: Long) {}
        override fun driver(screen: PhoneScreen) = null
        override fun gate() = null
        override fun system(): SystemApi = error("unexpected system call")
    }, PhoneTasks(), ActionLog()).apply {
        rootShell = RootShell { argv ->
            spawns += argv
            Fake(argv.last(), stdout, stderr, exit, hang)
        }
    }

    @After fun close() = core.worker.shutdown()

    private fun shell(args: Map<String, Any?>) = core.call(PhoneToolCatalog.SHELL, args)

    @Test fun refusedWhileTheRuleIsOffAndNothingIsSpawned() {
        val out = shell(mapOf("command" to "id"))
        assertTrue(out.isError)
        assertTrue(out.text.contains("完整 root 没打开"))
        assertTrue(spawns.isEmpty())
    }

    @Test fun runsTheCommandVerbatimOnceTheRuleIsOn() {
        policies = policies.copy(unlocks = PhoneUnlocks(rootShell = true))
        val out = shell(mapOf("command" to "id"))
        assertFalse(out.isError)
        assertEquals(listOf(listOf("su", "-c", "id")), spawns)
        assertTrue(out.text.contains("exit 0"))
        assertTrue(out.text.contains("uid=0(root)"))
    }

    @Test fun cwdPrefixesACdAndQuotesIt() {
        policies = policies.copy(unlocks = PhoneUnlocks(rootShell = true))
        shell(mapOf("command" to "ls", "cwd" to "/data/anr"))
        assertEquals(listOf(listOf("su", "-c", "cd '/data/anr' && ls")), spawns)
    }

    @Test fun stderrAndExitCodeReachTheModel() {
        policies = policies.copy(unlocks = PhoneUnlocks(rootShell = true))
        stdout = ""
        stderr = "ls: /data/nope: No such file or directory\n"
        exit = 2
        val out = shell(mapOf("command" to "ls /data/nope"))
        assertTrue(out.isError)
        assertTrue(out.text.contains("exit 2"))
        assertTrue(out.text.contains("stderr:"))
        assertTrue(out.text.contains("No such file or directory"))
    }

    @Test fun timeoutKillsTheChildAndSaysSo() {
        policies = policies.copy(unlocks = PhoneUnlocks(rootShell = true))
        hang = true
        val out = shell(mapOf("command" to "sleep 99", "timeout_ms" to 1000))
        assertTrue(out.isError)
        assertTrue(out.text.contains("命令超时"))
    }

    @Test fun emptyCommandIsRejectedBeforeSpawning() {
        policies = policies.copy(unlocks = PhoneUnlocks(rootShell = true))
        val out = shell(mapOf("command" to "   "))
        assertTrue(out.isError)
        assertTrue(spawns.isEmpty())
    }

    @Test fun unlockRoundTripsThroughTheWireMap() {
        val lifted = PhoneUnlocks(rootShell = true)
        assertEquals(true, lifted.toMap()["rootShell"])
        assertTrue(lifted.any)
        assertEquals(lifted, PhoneUnlocks.fromMap(lifted.toMap()))
        assertFalse(PhoneUnlocks.fromMap(null).rootShell)
        assertFalse(PhoneUnlocks().any)
    }

    /** A Process with fixed output; [hang] keeps [waitFor] from finishing until destroyed. */
    private class Fake(
        private val command: String,
        out: String,
        err: String,
        private val code: Int,
        private val hang: Boolean,
    ) : Process() {
        private val stdout = ByteArrayInputStream(out.toByteArray())
        private val stderr = ByteArrayInputStream(err.toByteArray())
        @Volatile private var dead = false

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int { while (hang && !dead) Thread.sleep(5); return if (dead) 137 else code }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (!hang) return true
            val end = System.nanoTime() + unit.toNanos(timeout)
            while (!dead && System.nanoTime() < end) Thread.sleep(5)
            return dead || !hang
        }
        override fun exitValue(): Int = if (dead) 137 else code
        override fun destroy() { dead = true }
        override fun destroyForcibly(): Process { dead = true; return this }
        override fun isAlive(): Boolean = hang && !dead
        override fun toString(): String = "Fake($command)"
    }
}
