package com.briqt.moke.terminal

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.PowerManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.MainActivity
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.localized
import com.briqt.moke.data.Host
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.ui.MokeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Temporary real-SSH smoke; requires the loopback fixture and adb reverse. */
class ConnectionSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("Timed out: $label")
    }
    private val screenshotDirectory = "/sdcard/Download/moke-smoke-${System.currentTimeMillis()}"
    private fun capture(name: String) {
        val path = "$screenshotDirectory/$name.png"
        shell("mkdir -p $screenshotDirectory")
        shell("screencap -p $path")
        check((shell("stat -c %s $path").trim().toLongOrNull() ?: 0) > 0) {
            "Could not save screenshot to $path"
        }
        println("SMOKE_SCREENSHOT $path")
    }

    @Test fun screenOffDisconnectAndReconnectRestoresRealTmuxPane() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(
            "Needs isolated loopback SSH fixture (recoverySshPort) with adb reverse",
            args.containsKey("recoverySshPort"),
        )
        val sshPort = args.getString("recoverySshPort")!!.toInt()
        val controlPort = args.getString("recoveryControlPort")?.toInt() ?: 22230
        val runId = UUID.randomUUID().toString()
        val tmuxName = "smoke-recovery-$runId"
        val tmuxPaneTarget = "=$tmuxName:"
        val outputMarker = "MOKE_RESTORED_CONTENT_$runId"
        val app = instrumentation.targetContext.applicationContext as MokeApplication
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wasInteractive = powerManager.isInteractive
        val wasWhitelisted = powerManager.isIgnoringBatteryOptimizations(app.packageName)
        val oldStayAwake = shell("settings get global stay_on_while_plugged_in").trim()
        val automation = instrumentation.uiAutomation
        val oldAccessibilityFlags = automation.serviceInfo.flags
        val host = Host(label = "Moke smoke", host = "127.0.0.1", port = sshPort,
            username = "smoke", password = "smoke-only", persistence = SessionPersistence.TMUX,
            tmuxSessionName = tmuxName)
        val oldTrust = HostKeyPrompt.autoTrust
        val knownHosts = KnownHosts(app)
        val trustId = KnownHosts.idOf(host.host, host.port)
        val savedFingerprint = knownHosts.stored(trustId)
        var source: TermSession? = null
        var stayAwakeChanged = false
        var whitelistAdded = false
        var idleForced = false
        var notificationIdentityAdopted = false
        var failure: Throwable? = null
        try {
            instrumentation.runOnMainSync { HostKeyPrompt.autoTrust = true }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = oldAccessibilityFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            // Avoid a runtime grant/revoke (revoking kills the instrumented process),
            // while keeping the notification permission dialog out of the terminal UI.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationIdentityAdopted = true
                automation.adoptShellPermissionIdentity(Manifest.permission.POST_NOTIFICATIONS)
            }
            shell("input keyevent KEYCODE_WAKEUP")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[MokeViewModel::class.java]
                    val id = vm.openSession(host)
                    source = app.sessions.get(id)!!
                    vm.requestOpenSessions(id)
                }
                await("tmux attached") { source!!.tmuxAttached.value == true }
                val original = source!!
                val out = original.transport.exec("tmux display-message -p -t '$tmuxPaneTarget' '#{pane_id}:#{pane_pid}'")!!.trim()
                // Assemble the marker remotely so the echoed command cannot satisfy the output assertion.
                original.transport.exec("tmux send-keys -t '$tmuxPaneTarget' \"printf 'MOKE_RESTORED_CONTENT_%s\\\\n' '$runId'\" Enter")
                await("original terminal output") { original.session.emulator.screen.transcriptText.contains(outputMarker) }
                capture("smoke-before")
                stayAwakeChanged = true
                shell("settings put global stay_on_while_plugged_in 0")
                shell("input keyevent KEYCODE_SLEEP")
                SystemClock.sleep(35_000)
                assertTrue("SSH survives screen off", original.alive.value)
                assertEquals(out, original.transport.exec("tmux display-message -p -t '$tmuxPaneTarget' '#{pane_id}:#{pane_pid}'")!!.trim())
                val heldLocks = shell("dumpsys power")
                    .substringAfter("Wake Locks: size=", "")
                    .substringBefore("\n\n")
                assertTrue("Active session holds CPU lock", heldLocks.contains("moke:sessions"))
                if (!wasWhitelisted) {
                    whitelistAdded = true
                    shell("dumpsys deviceidle whitelist +${app.packageName}")
                }
                idleForced = true
                assertTrue(shell("dumpsys deviceidle force-idle").contains("idle"))
                SystemClock.sleep(10_000)
                assertEquals(out, original.transport.exec("tmux display-message -p -t '$tmuxPaneTarget' '#{pane_id}:#{pane_pid}'")!!.trim())
                shell("dumpsys deviceidle unforce")
                idleForced = false
                if (whitelistAdded) {
                    shell("dumpsys deviceidle whitelist -${app.packageName}")
                    whitelistAdded = false
                }
                Socket("127.0.0.1", controlPort).use { socket ->
                    socket.getOutputStream().write("drop\n".toByteArray())
                    socket.getInputStream().read()
                }
                await("transport ends") { !original.alive.value }
                SystemClock.sleep(1000)
                assertFalse("Ended tabs release CPU lock", shell("dumpsys power")
                    .substringAfter("Wake Locks: size=", "")
                    .substringBefore("\n\n").contains("moke:sessions"))
                shell("input keyevent KEYCODE_WAKEUP")
                shell("wm dismiss-keyguard")
                SystemClock.sleep(1500)
                shell("wm dismiss-keyguard")
                // 等 Activity 回到前台。
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[MokeViewModel::class.java].requestOpenSessions(original.id)
                }
                fun findReconnectNode(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                    if (node == null) return null
                    val t = node.text?.toString().orEmpty()
                    if (t.contains("Reconnect") || t.contains("重新连接")) return node
                    for (i in 0 until node.childCount) {
                        val child = findReconnectNode(node.getChild(i))
                        if (child != null) return child
                    }
                    return null
                }
                var reconnect: android.view.accessibility.AccessibilityNodeInfo? = null
                val bannerDeadline = SystemClock.elapsedRealtime() + 15_000
                while (SystemClock.elapsedRealtime() < bannerDeadline) {
                    val roots = (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)).distinct()
                    reconnect = roots.firstNotNullOfOrNull(::findReconnectNode)
                    if (reconnect != null) break
                    SystemClock.sleep(300)
                }
                checkNotNull(reconnect) { "Reconnect banner not reachable after requestOpenSessions" }
                var button = reconnect
                while (button != null && !button.isClickable) button = button.parent
                assertTrue(button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
                await("replacement connects and restores screen") {
                    app.sessions.sessions.value.any { it.host.id == host.id && it.id != original.id &&
                        it.tmuxAttached.value == true && it.session.emulator.screen.transcriptText.contains(outputMarker) }
                }
                val recovered = app.sessions.sessions.value.single { it.host.id == host.id }
                assertEquals(out, recovered.transport.exec("tmux display-message -p -t '$tmuxPaneTarget' '#{pane_id}:#{pane_pid}'")!!.trim())
                capture("smoke-after")
                fun joinTmuxAction(action: (MokeViewModel) -> Job) {
                    lateinit var job: Job
                    scenario.onActivity { activity ->
                        job = action(ViewModelProvider(activity)[MokeViewModel::class.java])
                    }
                    // Activity callbacks stay on main; only the instrumentation thread waits for IO.
                    runBlocking(Dispatchers.IO) { withTimeout(30_000) { job.join() } }
                }
                fun assertRenderedBanner(expected: String, forbidden: List<String>) {
                    fun texts(node: android.view.accessibility.AccessibilityNodeInfo?): List<String> {
                        if (node == null) return emptyList()
                        return listOfNotNull(node.text?.toString()) +
                            (0 until node.childCount).flatMap { texts(node.getChild(it)) }
                    }
                    val deadline = SystemClock.elapsedRealtime() + 15_000
                    var visibleTexts = emptyList<String>()
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val roots = (automation.windows.mapNotNull { it.root } +
                            listOfNotNull(automation.rootInActiveWindow)).distinct()
                        visibleTexts = roots.flatMap(::texts)
                        if (expected in visibleTexts) break
                        SystemClock.sleep(100)
                    }
                    assertTrue("Expected rendered banner '$expected'; visible=$visibleTexts", expected in visibleTexts)
                    forbidden.forEach { text ->
                        assertFalse("Unexpected rendered banner '$text'; visible=$visibleTexts",
                            visibleTexts.any { it.contains(text) })
                    }
                }
                val attachUnconfirmed = app.localized(R.string.tmux_attach_unconfirmed)
                val connectFailed = app.localized(R.string.session_connect_failed)
                val leftTmux = app.localized(R.string.tmux_left)
                val sessionEnded = app.localized(R.string.session_ended)
                joinTmuxAction { it.refreshTmux(recovered) }
                val detachId = recovered.tmuxState.value.sessions.single { it.name == tmuxName }.id
                joinTmuxAction { it.tmuxDetach(recovered, detachId) }
                await("explicit detach ends the transport") { !recovered.alive.value }
                assertRenderedBanner(leftTmux, listOf(attachUnconfirmed, connectFailed))
                capture("smoke-detached")

                val detachRoots = automation.windows.mapNotNull { it.root } +
                    listOfNotNull(automation.rootInActiveWindow)
                var detachReconnect = checkNotNull(detachRoots.firstNotNullOfOrNull(::findReconnectNode))
                while (!detachReconnect.isClickable) {
                    detachReconnect = checkNotNull(detachReconnect.parent)
                }
                assertTrue(detachReconnect.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                await("detach reconnect replaces the visible session") {
                    app.sessions.sessions.value.any { it.host.id == host.id && it.id != recovered.id }
                }
                val reattached = app.sessions.sessions.value.single { it.host.id == host.id }
                await("detached pane reconnects with its original content") {
                    reattached.alive.value && reattached.tmuxAttached.value == true &&
                        reattached.session.emulator.screen.transcriptText.contains(outputMarker)
                }
                assertEquals(out, reattached.transport.exec("tmux display-message -p -t '$tmuxPaneTarget' '#{pane_id}:#{pane_pid}'")!!.trim())
                joinTmuxAction { it.refreshTmux(reattached) }
                val killId = reattached.tmuxState.value.sessions.single { it.name == tmuxName }.id
                joinTmuxAction { it.tmuxKill(reattached, killId) }
                await("explicit kill ends the transport") { !reattached.alive.value }
                assertFalse("Killed terminal must not claim that the tmux session still exists",
                    reattached.session.emulator.screen.transcriptText.contains(app.localized(R.string.term_left_tmux)))
                assertRenderedBanner(sessionEnded, listOf(attachUnconfirmed, connectFailed, leftTmux))
                capture("smoke-killed")
                println("SMOKE_PASS screenOff=35s exemptDoze=10s realSSH=true sameTmuxPane=$out UIReconnect=true contentRestored=true locksReleased=true detachBanner=true killBanner=true")
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val primaryFailure = failure
            fun restore(action: () -> Unit) {
                try {
                    action()
                } catch (cleanupFailure: Throwable) {
                    val previous = failure
                    if (previous == null) failure = cleanupFailure
                    else previous.addSuppressed(cleanupFailure)
                }
            }
            if (primaryFailure != null) restore { capture("smoke-failure") }
            if (idleForced) restore { shell("dumpsys deviceidle unforce") }
            if (whitelistAdded) restore { shell("dumpsys deviceidle whitelist -${app.packageName}") }
            if (stayAwakeChanged) restore {
                if (oldStayAwake == "null") shell("settings delete global stay_on_while_plugged_in")
                else shell("settings put global stay_on_while_plugged_in $oldStayAwake")
            }
            restore {
                automation.serviceInfo = automation.serviceInfo.apply { flags = oldAccessibilityFlags }
            }
            restore {
                instrumentation.runOnMainSync {
                    app.sessions.sessions.value.filter { it.host.id == host.id }.forEach { session ->
                        restore { app.sessions.close(session.id) }
                    }
                }
            }
            restore {
                // Never rely on the foreground transport: the smoke deliberately disconnects it.
                SshConnector(app).use(host, null) { client ->
                    client.startSession().use { session ->
                        val command = session.exec(
                            "if tmux has-session -t '=$tmuxName' 2>/dev/null; then " +
                                "tmux kill-session -t '=$tmuxName' || exit 1; fi; " +
                                "! tmux has-session -t '=$tmuxName' 2>/dev/null",
                        )
                        command.join(10, TimeUnit.SECONDS)
                        assertFalse("Smoke tmux cleanup timed out", command.isOpen)
                        assertEquals("Smoke tmux session was not removed: $tmuxName", 0, command.exitStatus)
                    }
                }
            }
            restore { instrumentation.runOnMainSync { HostKeyPrompt.autoTrust = oldTrust } }
            restore {
                if (savedFingerprint == null) knownHosts.forget(trustId)
                else knownHosts.store(trustId, savedFingerprint)
            }
            if (notificationIdentityAdopted) restore { automation.dropShellPermissionIdentity() }
            restore { shell("input keyevent ${if (wasInteractive) "KEYCODE_WAKEUP" else "KEYCODE_SLEEP"}") }
            if (primaryFailure == null) failure?.let { throw it }
        }
    }
}
