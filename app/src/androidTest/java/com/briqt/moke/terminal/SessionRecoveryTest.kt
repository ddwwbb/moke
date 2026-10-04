package com.briqt.moke.terminal

import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.briqt.moke.MokeApplication
import com.briqt.moke.data.Host
import com.briqt.moke.ui.MokeViewModel
import org.junit.Assert.assertEquals
import com.briqt.moke.data.SessionPersistence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionRecoveryTest {
    @Test
    fun reconnectUsesLatestTmuxIdentityAndKeepsVisibleSessionState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val app = instrumentation.targetContext.applicationContext as MokeApplication
            val viewModelStore = ViewModelStore()
            val vm = ViewModelProvider(
                viewModelStore,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app),
            )[MokeViewModel::class.java]
            val manager = vm.sessions
            val host = Host(host = "recovery.invalid", username = "test")
            val ids = mutableSetOf<String>()
            try {
                val before = manager.open(host).also { ids += it.id }
                val source = manager.openTmux(
                    before,
                    TmuxSession("\$7", "original", 1, 1, 1),
                ).also { ids += it.id }
                val after = manager.open(host).also { ids += it.id }
                source.setCustomTitle("My editor")
                source.composerDraft.value = "echo unsent"
                source.draftNeedsReview.value = true
                source.pendingDraftUploads.add("pending-upload")
                source.negotiatedTerm.value = "screen-256color"
                source.tmuxAttached.value = true
                manager.renameTmuxAssociation(host.id, "\$7", "renamed-in-panel")
                manager.reconcileTmuxAssociations(
                    host.id,
                    listOf(TmuxSession("\$7", "renamed-remotely", 1, 1, 1)),
                )
                source.controller.onSessionFinished(source.session)
                assertFalse(source.alive.value)
                manager.reconcileTmuxAssociations(host.id, listOf(TmuxSession("\$7", "unrelated", 1, 0, 2)))
                assertEquals("renamed-remotely", source.tmuxRecovery.value!!.name)
                assertNull(source.remoteTmuxName.value)
                val recovered = manager.get(vm.reconnectSession(source))!!
                ids += recovered.id
                assertEquals("renamed-remotely", recovered.remoteTmuxName.value)
                assertEquals("renamed-remotely", recovered.tmuxRecovery.value!!.name)
                assertEquals("My editor", recovered.customTitle.value)
                assertEquals("My editor", recovered.displayTitle.value)
                assertEquals("echo unsent", recovered.composerDraft.value)
                assertTrue(recovered.pendingDraftUploads.contains("pending-upload"))
                assertTrue(source.pendingDraftUploads.isEmpty())
                assertTrue(recovered.draftNeedsReview.value)
                assertEquals("screen-256color", recovered.negotiatedTerm.value)
                assertNull(recovered.copyMark)
                assertNull(manager.get(source.id))
                manager.reconcileTmuxAssociations(host.id, emptyList())
                assertNull(recovered.remoteTmuxName.value)
                assertEquals("renamed-remotely", recovered.tmuxRecovery.value!!.name)
                val retry = manager.get(vm.reconnectSession(recovered))!!
                ids += retry.id
                assertEquals("renamed-remotely", retry.remoteTmuxName.value)
                assertEquals("echo unsent", retry.composerDraft.value)
                assertEquals(listOf(before.id, retry.id, after.id),
                    manager.sessions.value.filter { it.host.id == host.id }.map { it.id })
                val shell = manager.open(host.copy(startupCommand = "cmd.exe /k"), startupCommand = "custom-program --interactive")
                    .also { ids += it.id }
                val shellRetry = manager.get(vm.reconnectSession(shell))!!.also { ids += it.id }
                assertEquals("custom-program --interactive", shellRetry.startupCommand)
                assertNull(shellRetry.tmuxRecovery.value)
            } finally {
                ids.forEach(manager::close)
                viewModelStore.clear()
            }
        }
    }

    @Test
    fun disconnectedRecoveryIgnoresRenameOfReusedId() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val manager = (instrumentation.targetContext.applicationContext as MokeApplication).sessions
            val host = Host(host = "rename-identity.invalid", username = "test")
            try {
                val source = manager.open(host, remoteTmuxId = "\$0", remoteTmuxName = "editor")
                source.tmuxAttached.value = true
                source.controller.onSessionFinished(source.session)
                manager.renameTmuxAssociation(host.id, "\$0", "unrelated-renamed")
                manager.reconcileTmuxAssociations(host.id, listOf(
                    TmuxSession("\$0", "unrelated-renamed", 1, 0, 2),
                    TmuxSession("\$1", "editor", 1, 0, 2),
                ))
                val recovered = manager.reconnect(source)
                assertEquals("editor", recovered.tmuxRecovery.value!!.name)
                assertEquals("editor", recovered.remoteTmuxName.value)
            } finally {
                manager.sessions.value.filter { it.host.id == host.id }.forEach { manager.close(it.id) }
            }
        }
    }

    @Test
    fun reconnectRejectsReusedIdsAndExplicitDetachStaysUnassociated() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val manager = (instrumentation.targetContext.applicationContext as MokeApplication).sessions
            val host = Host(host = "identity.invalid", username = "test")
            try {
                val source = manager.open(host, remoteTmuxId = "\$0", remoteTmuxName = "original")
                source.tmuxAttached.value = true
                source.controller.onSessionFinished(source.session)
                val replacement = manager.reconnect(source)
                manager.reconcileTmuxAssociations(host.id, listOf(TmuxSession("\$0", "unrelated", 1, 0, 2)))
                assertNull(replacement.remoteTmuxId.value)
                assertNull(replacement.remoteTmuxName.value)
                assertEquals("original", replacement.tmuxRecovery.value!!.name)

                val attached = manager.open(host, remoteTmuxId = "\$1", remoteTmuxName = "editor")
                attached.tmuxAttached.value = true
                manager.clearTmuxAssociation(host.id, "\$1", "editor", TmuxEndReason.DETACHED)
                manager.reconcileTmuxAssociations(host.id, listOf(TmuxSession("\$1", "editor", 1, 0, 1)))
                assertNull(attached.remoteTmuxId.value)
                assertNull(attached.remoteTmuxName.value)
                assertEquals(false, attached.tmuxAttached.value)
                assertEquals(TmuxEndReason.DETACHED, attached.tmuxEndReason.value)
                assertEquals("editor", manager.reconnect(attached).tmuxRecovery.value!!.name)
            } finally {
                manager.sessions.value.filter { it.host.id == host.id }.forEach { manager.close(it.id) }
            }
        }
    }

    @Test
    fun queuedUploadCompletionFollowsReplacementAfterViewModelIsCleared() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MokeApplication
        val host = Host(host = "uploads.invalid", username = "test")
        val viewModelStore = ViewModelStore()
        lateinit var replacement: TermSession
        try {
            instrumentation.runOnMainSync {
                val vm = ViewModelProvider(viewModelStore,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MokeViewModel::class.java]
                val source = app.sessions.open(host)
                source.composerDraft.value = "cat"
                source.pendingDraftUploads.add("upload-before-reconnect")
                // IO 回调已经返回，但主线程仍在替换会话；结果不得留在旧对象或被 ViewModel 取消。
                val completion = Thread {
                    vm.transfers.onUploadDone!!.invoke("upload-before-reconnect", host.id, "/tmp/file name.txt")
                }
                completion.start()
                completion.join()
                replacement = app.sessions.reconnect(source)
                viewModelStore.clear()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("cat '/tmp/file name.txt'", replacement.composerDraft.value)
                assertTrue(replacement.draftNeedsReview.value)
                assertFalse(replacement.pendingDraftUploads.contains("upload-before-reconnect"))
            }
        } finally {
            instrumentation.runOnMainSync {
                app.sessions.sessions.value.filter { it.host.id == host.id }.forEach { app.sessions.close(it.id) }
                viewModelStore.clear()
            }
        }
    }

    /** Requires an isolated loopback SSH fixture with tmux and adb reverse for this port. */
    @Test
    fun realSshRecoveryRetainsRenamedPaneAndReportsMissingOrConflictingWorkspace() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Needs isolated loopback SSH fixture", args.containsKey("recoverySshPort"))
        val port = args.getString("recoverySshPort")!!.toInt()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MokeApplication
        val viewModelStore = ViewModelStore()
        lateinit var vm: MokeViewModel
        lateinit var source: TermSession
        val host = Host(host = "127.0.0.1", port = port, username = "smoke", password = "smoke-only",
            persistence = SessionPersistence.TMUX)
        val prefix = "recovery-${UUID.randomUUID()}"
        val selected = "$prefix-selected"
        val renamed = "$prefix-renamed"
        val externalName = "$prefix-external"
        val projectHost = host.copy(projectPath = "/tmp")
        val projectName = Tmux.projectSessionName(projectHost)
        val oldTrust = HostKeyPrompt.autoTrust
        val knownHosts = KnownHosts(app)
        val knownHostId = KnownHosts.idOf(host.host, host.port)
        val oldFingerprint = knownHosts.stored(knownHostId)
        fun main(action: () -> Unit) = instrumentation.runOnMainSync { action() }
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 25_000
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                main { ready = predicate() }
                if (ready) return
                SystemClock.sleep(100)
            }
            error("Timed out: $label")
        }
        fun awaitClients(observer: TermSession, target: String) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (observer.transport.exec(Tmux.clientsCmd(TmuxRecovery(target)))?.trim() == "1") return
                SystemClock.sleep(100)
            }
            error("Timed out waiting for client of $target")
        }
        fun start(session: TermSession) = main { session.session.updateSize(80, 24, 8, 16) }
        fun screen(session: TermSession, text: String) =
            session.session.emulator?.screen?.transcriptText?.contains(text) == true
        fun disconnect(session: TermSession) {
            main { session.session.finishIfRunning() }
            await("transport ended") { !session.alive.value }
        }
        fun recover(session: TermSession): TermSession {
            lateinit var replacement: TermSession
            main { replacement = vm.sessions.get(vm.reconnectSession(session))!! }
            start(replacement)
            return replacement
        }
        try {
            main {
                HostKeyPrompt.autoTrust = true
                vm = ViewModelProvider(viewModelStore,
                    ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MokeViewModel::class.java]
                source = vm.sessions.get(vm.openSession(host))!!
            }
            start(source)
            await("initial tmux picker") { vm.tmuxPicker.value == source.id }
            disconnect(source)
            source = recover(source)
            await("picker offered again after reconnect") { vm.tmuxPicker.value == source.id }
            assertTrue(Tmux.parseAction(source.transport.exec(Tmux.actionCmd(Tmux.newCmd(selected)))!!)!!.ok)
            runBlocking { vm.refreshTmux(source).join() }
            main { source = vm.sessions.get(vm.pickTmuxSession(source.id, selected)!!)!! }
            start(source)
            await("selected tmux attached") { source.tmuxAttached.value == true }
            val pane = source.transport.exec("tmux display-message -p -t '$selected' '#{pane_id}:#{pane_pid}'")!!.trim()
            source.transport.exec("tmux send-keys -t '$selected' 'echo MOKE_RENAMED_CONTENT' Enter")
            await("original content") { screen(source, "MOKE_RENAMED_CONTENT") }
            val id = source.transport.exec("tmux display-message -p -t '$selected' '#{session_id}'")!!.trim()
            runBlocking { vm.tmuxRename(source, id, renamed).join() }
            assertEquals(renamed, source.remoteTmuxName.value)
            assertTrue(Tmux.parseAction(source.transport.exec(Tmux.actionCmd(Tmux.renameCmd(id, externalName)))!!)!!.ok)
            runBlocking { vm.refreshTmux(source).join() }
            assertEquals(externalName, source.remoteTmuxName.value)
            main { source.setCustomTitle("Editor"); source.composerDraft.value = "unsent" }
            disconnect(source)
            source = recover(source)
            await("renamed content restored") { source.tmuxAttached.value == true && screen(source, "MOKE_RENAMED_CONTENT") }
            assertEquals(pane, source.transport.exec("tmux display-message -p -t '$externalName' '#{pane_id}:#{pane_pid}'")!!.trim())
            assertEquals("Editor", source.displayTitle.value)
            assertEquals("unsent", source.composerDraft.value)
            runBlocking { vm.refreshTmux(source).join(); vm.tmuxDetach(source, id).join() }
            await("explicit detach ended transport") { !source.alive.value }
            assertNull(source.tmuxState.value.message)
            assertNull(source.remoteTmuxName.value)
            assertEquals(TmuxEndReason.DETACHED, source.tmuxEndReason.value)
            assertEquals(externalName, source.tmuxRecovery.value!!.name)
            source = recover(source)
            await("explicitly detached pane can be recovered") { source.tmuxAttached.value == true }
            assertEquals(pane, source.transport.exec("tmux display-message -p -t '=$externalName:' '#{pane_id}:#{pane_pid}'")!!.trim())

            assertTrue(Tmux.parseAction(source.transport.exec(Tmux.actionCmd(Tmux.newCmd("$externalName-long")))!!)!!.ok)
            lateinit var prefixClient: TermSession
            main {
                prefixClient = app.sessions.open(host,
                    startupCommand = Tmux.attachExistingCommand(TmuxRecovery("$externalName-long")))
            }
            start(prefixClient)
            awaitClients(source, "$externalName-long")
            runBlocking { vm.refreshTmux(source).join(); vm.tmuxKill(source, id).join() }
            await("remote session ended") { !source.alive.value }
            assertEquals(TmuxEndReason.KILLED, source.tmuxEndReason.value)
            source = recover(source)
            await("missing session is visible") { screen(source, "previous tmux session not found: $externalName") }
            await("missing session is not confirmed via prefix client") { source.tmuxAttached.value == false }
            assertNull(source.remoteTmuxName.value)
            val remote = Tmux.parseDiscovery(source.transport.exec(Tmux.DISCOVER_CMD)!!) as TmuxDiscovery.Ready
            assertFalse(remote.sessions.any { it.name in listOf(selected, renamed, externalName) })
            assertEquals(1, remote.sessions.single { it.name == "$externalName-long" }.clients)
            assertTrue(Tmux.parseAction(source.transport.exec(Tmux.actionCmd(Tmux.newCmd("$projectName-long")))!!)!!.ok)
            source.transport.exec("tmux set-option -t '=$projectName-long' @moke_project_path '/tmp'")

            lateinit var project: TermSession
            main { project = vm.sessions.get(vm.openProject(projectHost))!! }
            start(project)
            await("project attached") { project.tmuxAttached.value == true }
            val projects = Tmux.parseDiscovery(project.transport.exec(Tmux.DISCOVER_CMD)!!) as TmuxDiscovery.Ready
            assertEquals(1, projects.sessions.single { it.name == projectName }.clients)
            assertEquals(0, projects.sessions.single { it.name == "$projectName-long" }.clients)
            val projectPane = project.transport.exec("tmux display-message -p -t '$projectName' '#{pane_id}:#{pane_pid}'")!!.trim()
            project.transport.exec("tmux set-option -t '$projectName' @moke_project_path '/different-workspace'")
            disconnect(project)
            project = recover(project)
            await("workspace mismatch is visible") { screen(project, "project session name conflicts with another directory") }
            lateinit var conflictingClient: TermSession
            main {
                conflictingClient = app.sessions.open(host,
                    startupCommand = Tmux.attachExistingCommand(TmuxRecovery(projectName)))
            }
            start(conflictingClient)
            awaitClients(project, projectName)
            await("workspace mismatch cannot be confirmed by another client") { project.tmuxAttached.value == false }
            assertEquals(projectPane, project.transport.exec("tmux display-message -p -t '$projectName' '#{pane_id}:#{pane_pid}'")!!.trim())
            assertEquals("1", project.transport.exec("tmux display-message -p -t '$projectName' '#{session_attached}'")!!.trim())
            project.transport.exec(Tmux.killCmd("$projectName"))
        } finally {
            val control = app.sessions.sessions.value.lastOrNull { it.host.id == host.id && it.alive.value }
            control?.transport?.exec(Tmux.killCmd("=$selected"))
            control?.transport?.exec(Tmux.killCmd("=$renamed"))
            control?.transport?.exec(Tmux.killCmd("=$externalName"))
            control?.transport?.exec(Tmux.killCmd("=$projectName"))
            control?.transport?.exec(Tmux.killCmd("=$externalName-long"))
            control?.transport?.exec(Tmux.killCmd("=$projectName-long"))
            main {
                app.sessions.sessions.value.filter { it.host.id == host.id }.forEach { app.sessions.close(it.id) }
                HostKeyPrompt.autoTrust = oldTrust
                if (oldFingerprint == null) knownHosts.forget(knownHostId)
                else knownHosts.store(knownHostId, oldFingerprint)
                viewModelStore.clear()
            }
        }
    }
}
