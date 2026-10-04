package com.briqt.moke.terminal

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.data.Host
import com.termux.terminal.TerminalSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SshLifecycleTest {
    @Test
    fun execBeforeConnectionDoesNotPreventDisconnectAfterInteractiveEof() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Needs isolated loopback SSH fixture", args.containsKey("recoverySshPort"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = Host(host = "127.0.0.1", port = args.getString("recoverySshPort")!!.toInt(),
            username = "smoke", password = "smoke-only")
        val established = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val transport = SshTransport(host, context, startupCommand = "sh -c 'read line'")
        transport.onEstablished = { established.countDown() }
        val knownHosts = KnownHosts(context)
        val trustId = KnownHosts.idOf(host.host, host.port)
        val savedFingerprint = knownHosts.stored(trustId)
        val oldTrust = HostKeyPrompt.autoTrust
        HostKeyPrompt.autoTrust = true
        try {
            repeat(3) {
                assertNull("Exec before connection must report not ready", transport.exec("printf unexpected"))
            }
            instrumentation.runOnMainSync {
                val controller = TerminalController(context, onFinished = { finished.countDown() })
                TerminalSession(transport, 100, controller).updateSize(80, 24, 8, 16)
            }
            assertTrue("SSH was not established", established.await(20, TimeUnit.SECONDS))
            // Retain the actual client: clearing the transport's reference alone must not pass this test.
            val client = requireNotNull(transport.acquireForwardClient()) { "Established SSH client is unavailable" }
            assertTrue("SSH client must be connected before EOF", client.isConnected)
            assertEquals("ready", transport.exec("printf ready"))
            val input = "\r".toByteArray(StandardCharsets.UTF_8)
            transport.write(input, 0, input.size)
            assertTrue("Main channel did not receive EOF", finished.await(10, TimeUnit.SECONDS))
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (client.isConnected && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(50)
            }
            assertFalse("Interactive EOF must disconnect the actual SSH client", client.isConnected)
            assertNull("Finished transport must reject commands", transport.exec("printf unexpected"))
        } finally {
            transport.close()
            HostKeyPrompt.autoTrust = oldTrust
            if (savedFingerprint == null) knownHosts.forget(trustId)
            else knownHosts.store(trustId, savedFingerprint)
        }
    }

    /** Requires the same isolated tmux SSH fixture and adb reverse as the recovery tests. */
    @Test
    fun detachEofDrainsRunningExecAndKeepsRemotePane() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Needs isolated loopback SSH fixture", args.containsKey("recoverySshPort"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host = Host(host = "127.0.0.1", port = args.getString("recoverySshPort")!!.toInt(),
            username = "smoke", password = "smoke-only")
        val name = "ssh-lifecycle-${UUID.randomUUID()}"
        val marker = "MOKE_EXEC_AFTER_EOF"
        val established = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val transport = SshTransport(host, context, startupCommand = "tmux attach-session -t '$name'")
        transport.onEstablished = { established.countDown() }
        val knownHosts = KnownHosts(context)
        val trustId = KnownHosts.idOf(host.host, host.port)
        val savedFingerprint = knownHosts.stored(trustId)
        val oldTrust = HostKeyPrompt.autoTrust
        HostKeyPrompt.autoTrust = true
        try {
            SshConnector(context).use(host, null) { observer ->
                fun exec(command: String): String = observer.startSession().use { session ->
                    val commandChannel = session.exec(command)
                    commandChannel.join(10, TimeUnit.SECONDS)
                    assertFalse("Fixture command timed out", commandChannel.isOpen)
                    assertEquals("Fixture command failed", 0, commandChannel.exitStatus)
                    commandChannel.inputStream.readBytes().toString(StandardCharsets.UTF_8)
                }
                fun awaitAttached() {
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (exec("tmux list-clients -t '$name' -F '#{client_name}'").isNotBlank()) return
                        SystemClock.sleep(50)
                    }
                    error("Timed out waiting for the tmux client")
                }
                exec("tmux new-session -d -s '$name'")
                try {
                    val pane = exec("tmux display-message -p -t '$name' '#{pane_id}:#{pane_pid}'").trim()
                    instrumentation.runOnMainSync {
                        val controller = TerminalController(context, onFinished = { finished.countDown() })
                        TerminalSession(transport, 100, controller).updateSize(80, 24, 8, 16)
                    }
                    assertTrue("SSH was not established", established.await(20, TimeUnit.SECONDS))
                    awaitAttached()
                    // The management channel remains live for a second after detach ends the PTY channel.
                    val result = worker.submit<String?> {
                        transport.exec("tmux detach-client -s '$name'; sleep 1; printf '$marker'")
                    }
                    assertTrue("Main channel did not receive EOF", finished.await(10, TimeUnit.SECONDS))
                    // The UI finish callback can precede finishInteractive; wait for the real queue shutdown.
                    val writes = SshTransport::class.java.getDeclaredField("writeExec").run {
                        isAccessible = true
                        get(transport) as ExecutorService
                    }
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    while (!writes.isShutdown && SystemClock.elapsedRealtime() < deadline) {
                        SystemClock.sleep(10)
                    }
                    assertTrue("EOF must shut down the write queue", writes.isShutdown)
                    assertFalse("Management command should still be running at main EOF", result.isDone)
                    assertNull("EOF must reject new commands", transport.exec("printf unexpected"))
                    val lateInput = "ignored\r".toByteArray(StandardCharsets.UTF_8)
                    transport.write(lateInput, 0, lateInput.size)
                    assertEquals(marker, result.get(15, TimeUnit.SECONDS))
                    assertEquals(pane, exec("tmux display-message -p -t '$name' '#{pane_id}:#{pane_pid}'").trim())
                    assertEquals("0", exec("tmux display-message -p -t '$name' '#{session_attached}'").trim())
                    instrumentation.runOnMainSync { transport.close(); transport.close() }
                    assertNull("Closed transport must reject commands", transport.exec("printf unexpected"))
                } finally {
                    transport.close()
                    exec("tmux kill-session -t '$name'")
                }
            }
        } finally {
            transport.close()
            worker.shutdownNow()
            HostKeyPrompt.autoTrust = oldTrust
            if (savedFingerprint == null) knownHosts.forget(trustId)
            else knownHosts.store(trustId, savedFingerprint)
        }
    }
}
