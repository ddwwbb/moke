package com.briqt.moke.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.briqt.moke.MokeApplication
import com.briqt.moke.R
import com.briqt.moke.localized
import com.briqt.moke.data.Host
import com.briqt.moke.terminal.HostKeyPrompt
import com.briqt.moke.terminal.KnownHosts
import com.briqt.moke.terminal.MokeSessionService
import com.briqt.moke.terminal.MokeTransferService
import com.briqt.moke.terminal.TermSession
import com.briqt.moke.terminal.Tmux
import com.briqt.moke.terminal.TmuxDiscovery
import com.briqt.moke.terminal.TmuxPhase
import com.briqt.moke.terminal.TmuxSession
import com.briqt.moke.data.FilesSort
import com.briqt.moke.data.ExtraKeysLayout
import com.briqt.moke.data.GroupBy
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.data.SortBy
import com.briqt.moke.data.HostStore
import com.briqt.moke.data.ScrollMode
import com.briqt.moke.data.SessionPersistence
import com.briqt.moke.data.SettingsStore
import com.briqt.moke.data.ThemeMode
import com.briqt.moke.terminal.FontRepository
import com.briqt.moke.terminal.TerminalThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import com.briqt.moke.data.HostMigration
import com.briqt.moke.terminal.GitDiff
import com.briqt.moke.terminal.GitDiffResult

class MokeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HostStore(app)
    private val settings = SettingsStore(app)
    val fonts = FontRepository(app)

    /** 取本地化字符串（随**应用内**语言，而非系统语言——ViewModel 手里只有 Application context）。 */
    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().localized(id, *args)

    /** 多会话管理器：Application 作用域单例，跨导航/Activity 存活，配合前台服务后台保活。 */
    val sessions = (app as MokeApplication).sessions

    /**
     * 待处理的"回到会话"请求（点后台保活通知时由 MainActivity 发出）。存成状态而不是一次性事件：
     * 冷启动时请求先于界面组合到达，事件会丢；界面处理完调 [consumeOpenSessions] 清掉，避免重建时重跳。
     */
    private val _openSessionsRequest = MutableStateFlow<OpenSessionsRequest?>(null)
    val openSessionsRequest: StateFlow<OpenSessionsRequest?> = _openSessionsRequest.asStateFlow()
    fun requestOpenSessions(sessionId: String? = null) { _openSessionsRequest.value = OpenSessionsRequest(sessionId) }
    fun consumeOpenSessions() { _openSessionsRequest.value = null }

    /** [sessionId] 非空 = 指明要回到哪个会话（终端提醒）；为空 = 按会话数决定落点（保活通知）。 */
    data class OpenSessionsRequest(val sessionId: String?)

    val hosts: StateFlow<List<Host>> = store.hosts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 存量凭据解不开（Keystore 密钥失效，典型是整机备份恢复到新机）。
     * 连接页据此说清原因——否则用户只看到一个空列表，会以为数据自己没了。
     */
    val credentialsUnreadable: StateFlow<Boolean> = store.unreadable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 用户选的配色：关闭联动时即最终生效者；开启联动后是「深色模式用」那一套。 */
    val colorSchemeId: StateFlow<String> = settings.colorSchemeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalThemes.DEFAULT_ID)

    /** 「浅色模式用」配色（仅联动开启时参与）。 */
    val lightColorSchemeId: StateFlow<String> = settings.lightColorSchemeId
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalThemes.DEFAULT_LIGHT_ID)

    /** 配色是否随应用明暗联动。 */
    val schemeFollowsTheme: StateFlow<Boolean> = settings.schemeFollowsTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 应用当前是否深色。由 UI 层（已把「跟随系统/浅色/深色」解析成布尔）回灌——
     * `isSystemInDarkTheme()` 只能在 Compose 里读，故不在 VM 内自行判断。
     */
    private val _appIsDark = MutableStateFlow(true)
    fun setAppIsDark(dark: Boolean) { _appIsDark.value = dark }

    /**
     * 最终生效的配色 id：联动开启且当前浅色 → 用浅色那套，否则用主选择。
     * **全局终端调色板只在这里注入**（含启动首值），避免两处各写一遍打架。
     */
    val effectiveSchemeId: StateFlow<String> =
        combine(settings.colorSchemeId, settings.lightColorSchemeId, settings.schemeFollowsTheme, _appIsDark) { dark, light, follows, isDark ->
            if (follows && !isDark) light else dark
        }
            .onEach { id -> TerminalThemes.byId(id).applyToTerminal() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalThemes.DEFAULT_ID)

    val fontSizeSp: StateFlow<Float> = settings.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_FONT_SIZE_SP)

    val cursorStyle: StateFlow<Int> = settings.cursorStyle
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val cursorBlink: StateFlow<Boolean> = settings.cursorBlink
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val extraKeysVisible: StateFlow<Boolean> = settings.extraKeysVisible
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val extraKeysLayout: StateFlow<ExtraKeysLayout> = settings.extraKeysLayout
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExtraKeysLayout.DEFAULT)
    val customExtraKeyIds: StateFlow<List<String>> = settings.customExtraKeyIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 应用明暗主题 / 动态取色 / 键盘模式 / 关闭会话二次确认。 */
    val themeMode: StateFlow<ThemeMode> = settings.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val dynamicColor: StateFlow<Boolean> = settings.dynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val keyboardMode: StateFlow<KeyboardMode> = settings.keyboardMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, KeyboardMode.SECURE)
    val scrollMode: StateFlow<ScrollMode> = settings.scrollMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, ScrollMode.SMART)
    val tmuxScrollSetup: StateFlow<Boolean> = settings.tmuxScrollSetup
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val confirmCloseSession: StateFlow<Boolean> = settings.confirmCloseSession
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val keepScreenOn: StateFlow<Boolean> = settings.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * 首连是否自动信任主机密钥（默认关=先问一次）。
     *
     * 值的镜像（[HostKeyPrompt.autoTrust]）由 `MokeApplication` 在进程作用域维护：校验发生在
     * 连接线程上、必须同步返回，不能在那儿读 DataStore；也不能只依赖 ViewModel 活着。
     */
    val autoTrustNewHostKey: StateFlow<Boolean> = settings.autoTrustNewHostKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 待确认的首连指纹（非空=显示确认弹窗）。 */
    val hostKeyRequest: StateFlow<HostKeyPrompt.Request?> = HostKeyPrompt.pending

    fun resolveHostKey(id: String, trusted: Boolean) = HostKeyPrompt.resolve(id, trusted)
    /** 修复 rc.1 可能把运行态 `tmux attach-session -t '$N'` 污染进保存连接的问题。 */
    private fun repairLegacyTmuxLoginCommands() = viewModelScope.launch(Dispatchers.IO) {
        val current = store.hosts.first()
        val repaired = current.map { host ->
            if (Tmux.isLegacyInjectedLoginCommand(host.loginCommand)) {
                host.copy(loginCommand = "")
            } else {
                host
            }
        }
        if (repaired != current) store.save(repaired)
    }

    // 连接页：固定按项目分组，仅持久化「分组顺序」与「已折叠分组」。
    val hostGroupOrder: StateFlow<List<String>> = settings.hostGroupOrder
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hostCollapsedGroups: StateFlow<Set<String>> = settings.hostCollapsedGroups
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    // 会话页：分组 / 排序两个正交维度，均持久化。
    val sessionGroupBy: StateFlow<GroupBy> = settings.sessionGroupBy
        .stateIn(viewModelScope, SharingStarted.Eagerly, GroupBy.PROJECT)
    val sessionSortBy: StateFlow<SortBy> = settings.sessionSortBy
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortBy.CREATED)

    // 会话分组的顺序/折叠：会话本身是内存态（重启即清空），故这两项也只放内存、不持久化。
    private val _sessionGroupOrder = MutableStateFlow<List<String>>(emptyList())
    val sessionGroupOrder: StateFlow<List<String>> = _sessionGroupOrder.asStateFlow()
    private val _sessionCollapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    val sessionCollapsedGroups: StateFlow<Set<String>> = _sessionCollapsedGroups.asStateFlow()

    val lineSpacing: StateFlow<Float> = settings.lineSpacing
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SPACING)

    val letterSpacing: StateFlow<Float> = settings.letterSpacing
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SPACING)

    private val _migrationPreview = MutableStateFlow<HostMigration.Preview?>(null)
    val migrationPreview: StateFlow<HostMigration.Preview?> = _migrationPreview.asStateFlow()
    private val _migrationError = MutableStateFlow<String?>(null)
    val migrationError: StateFlow<String?> = _migrationError.asStateFlow()
    private val _migrationBusy = MutableStateFlow(false)
    val migrationBusy: StateFlow<Boolean> = _migrationBusy.asStateFlow()
    private val migrationMutex = kotlinx.coroutines.sync.Mutex()

    fun clearHostMigration() {
        if (_migrationBusy.value) return
        _migrationPreview.value = null
        _migrationError.value = null
    }

    fun importHostFile(uri: android.net.Uri) = viewModelScope.launch(Dispatchers.IO) {
        if (!migrationMutex.tryLock()) return@launch
        _migrationBusy.value = true
        _migrationPreview.value = null
        _migrationError.value = null
        try {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IllegalStateException(uri.toString())
            val text = input.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    require(buffer.size() + read <= MAX_HOST_MIGRATION_BYTES) { str(R.string.migration_file_too_large) }
                    buffer.write(chunk, 0, read)
                }
                buffer.toString(Charsets.UTF_8.name())
            }
            check(!store.unreadable.first()) { str(R.string.hosts_unreadable_title) }
            _migrationPreview.value = HostMigration.preview(text, store.hosts.first())
        } catch (t: Exception) {
            _migrationError.value = str(R.string.migration_read_failed, t.message ?: t.javaClass.simpleName)
        } finally {
            _migrationBusy.value = false
            migrationMutex.unlock()
        }
    }

    fun exportHostFile(uri: android.net.Uri) = viewModelScope.launch(Dispatchers.IO) {
        if (!migrationMutex.tryLock()) return@launch
        _migrationBusy.value = true
        _migrationError.value = null
        try {
            check(!store.unreadable.first()) { str(R.string.hosts_unreadable_title) }
            val body = HostMigration.export(store.hosts.first())
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException(uri.toString())
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        } catch (t: Exception) {
            _migrationError.value = str(R.string.migration_write_failed, t.message ?: t.javaClass.simpleName)
        } finally {
            _migrationBusy.value = false
            migrationMutex.unlock()
        }
    }

    fun applyHostMigration(decisions: Map<Int, HostMigration.Decision>) = viewModelScope.launch(Dispatchers.IO) {
        if (!migrationMutex.tryLock()) return@launch
        val preview = _migrationPreview.value
        if (preview == null) { migrationMutex.unlock(); return@launch }
        _migrationBusy.value = true
        try {
            if (!HostMigration.save(preview, decisions, store)) {
                _migrationError.value = str(R.string.migration_save_failed)
                return@launch
            }
            _migrationPreview.value = null
            _migrationError.value = str(R.string.migration_saved)
        } catch (t: Exception) {
            _migrationError.value = t.message ?: t.javaClass.simpleName
        } finally {
            _migrationBusy.value = false
            migrationMutex.unlock()
        }
    }

    /** Git Diff 变更状态（非空则在界面弹出 BottomSheet 审阅）。 */
    private val _gitDiffResult = MutableStateFlow<GitDiffResult?>(null)
    val gitDiffResult: StateFlow<GitDiffResult?> = _gitDiffResult.asStateFlow()

    private val _gitDiffLoading = MutableStateFlow(false)
    val gitDiffLoading: StateFlow<Boolean> = _gitDiffLoading.asStateFlow()

    fun dismissGitDiff() {
        _gitDiffResult.value = null
        _gitDiffLoading.value = false
    }

    fun loadGitDiff(ts: TermSession) = viewModelScope.launch(Dispatchers.IO) {
        if (_gitDiffLoading.value) return@launch
        _gitDiffLoading.value = true
        _gitDiffResult.value = null

        var targetDir = ts.host.projectPath.trim().trimEnd('/')
        if (targetDir.isBlank()) {
            val name = ts.remoteTmuxName.value
            if (!name.isNullOrBlank()) {
                val out = runCatching { ts.transport.exec(Tmux.paneCwdCmd(name)) }.getOrNull()?.trim().orEmpty()
                if (out.startsWith("/")) targetDir = out
            }
        }

        if (targetDir.isBlank()) {
            _gitDiffLoading.value = false
            _gitDiffResult.value = GitDiffResult.Error(str(R.string.git_diff_no_project_path))
            return@launch
        }

        val out = runCatching {
            ts.transport.exec(GitDiff.diffCommand(targetDir))
        }.getOrNull()

        _gitDiffLoading.value = false
        if (out == null) {
            _gitDiffResult.value = GitDiffResult.Error(str(R.string.tmux_control_unavailable))
        } else {
            _gitDiffResult.value = GitDiff.parse(out)
        }
    }

    fun save(host: Host) = viewModelScope.launch { store.upsert(host, hosts.value) }

    fun delete(host: Host) = viewModelScope.launch {
        store.delete(host, hosts.value)
        // 同时忘记指纹：否则服务器换密钥后"删除重建连接"依然连不上（指纹按 host:port 存，与条目无关）。
        if (hosts.value.none { it.id != host.id && it.host == host.host && it.port == host.port }) {
            knownHosts.forget(KnownHosts.idOf(host.host, host.port))
        }
    }

    private val knownHosts by lazy { KnownHosts(getApplication()) }

    /** 该主机已记录的指纹（null=还没记录）。 */
    fun savedFingerprint(host: Host): String? =
        knownHosts.stored(KnownHosts.idOf(host.host, host.port))

    /** 显式忘记指纹：服务器换过密钥时的自救路径（编辑页动作）。 */
    fun clearFingerprint(host: Host) {
        knownHosts.forget(KnownHosts.idOf(host.host, host.port))
    }

    /** 复制主机为新条目（label 加「副本」，新 id，清空最近连接时间）。 */
    fun duplicate(host: Host) = viewModelScope.launch {
        val copy = host.copy(
            id = java.util.UUID.randomUUID().toString(),
            label = (host.label.ifBlank { host.displayName }) + " " + str(R.string.duplicate_suffix),
            lastConnectedAt = 0L,
        )
        store.upsert(copy, hosts.value)
    }

    fun setSessionGroupBy(g: GroupBy) = viewModelScope.launch { settings.setSessionGroupBy(g) }
    fun setSessionSortBy(s: SortBy) = viewModelScope.launch { settings.setSessionSortBy(s) }

    /** 会话分组之间顺序（长按分组头拖动，内存态）。 */
    fun setSessionGroupOrder(order: List<String>) { _sessionGroupOrder.value = order }

    /** 折叠/展开某个会话分组（内存态）。 */
    fun toggleSessionGroupCollapsed(group: String) =
        _sessionCollapsedGroups.update { if (group in it) it - group else it + group }

    /** 连接页分组之间的顺序（长按分组头拖动后持久化）。 */
    fun setHostGroupOrder(order: List<String>) = viewModelScope.launch { settings.setHostGroupOrder(order) }

    /** 折叠/展开某个连接分组（跨重启记忆）。 */
    fun toggleHostGroupCollapsed(group: String) = viewModelScope.launch {
        val cur = hostCollapsedGroups.value
        settings.setHostCollapsedGroups(if (group in cur) cur - group else cur + group)
    }

    /** 手动拖动重排：持久化新的连接顺序（组内拖动 / 无分组平铺拖动均走此处）。 */
    fun reorderHosts(newOrder: List<Host>) = viewModelScope.launch { store.save(newOrder) }

    /** 拖动重排会话（仅内存，无持久化）。 */
    fun reorderSessions(orderedIds: List<String>) = sessions.reorder(orderedIds)

    // ---------- tmux 侧通道管理（SSH 复用现有连接；mosh 按需建立独立 SSH 控制连接）----------
    /**
     * 探测/刷新远端 tmux。状态明确区分检查中、未安装、零会话与失败；
     * 同一终端会话的刷新/增删改用 Mutex 串行，避免慢请求用旧列表覆盖新操作结果。
     */
    fun refreshTmux(ts: TermSession) = viewModelScope.launch(Dispatchers.IO) {
        ts.tmuxMutex.withLock { refreshTmuxLocked(ts, retryUntilReady = true) }
    }

    private suspend fun refreshTmuxLocked(ts: TermSession, retryUntilReady: Boolean) {
        val previous = ts.tmuxState.value
        ts.tmuxState.value = previous.copy(
            phase = if (previous.phase == TmuxPhase.READY) TmuxPhase.READY else TmuxPhase.CHECKING,
            busy = true,
            message = null,
        )

        // 重试是给「传输还没 start 完」留的窗口，不是给不可达主机用的压测。
        // 固定 1.5s 间隔在 20s 内会打满 13 次；mosh 主机上每一次都是一条完整 SSH 登录，
        // 对带 fail2ban / MaxStartups 的远端等于自我封禁。改成指数退避：0/1/3/7/15s 共 5 次。
        val deadline = System.currentTimeMillis() + if (retryUntilReady) 20_000L else 0L
        var out: String?
        var backoff = 1_000L
        while (true) {
            out = runCatching { ts.transport.exec(Tmux.DISCOVER_CMD) }.getOrNull()
            if (out != null || !retryUntilReady || !currentCoroutineContext().isActive) break
            if (System.currentTimeMillis() + backoff >= deadline) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(8_000L)
        }

        if (out == null) {
            ts.tmuxState.value = previous.copy(
                phase = if (previous.phase == TmuxPhase.READY) TmuxPhase.READY else TmuxPhase.ERROR,
                busy = false,
                message = str(R.string.tmux_control_unavailable),
            )
            return
        }

        ts.tmuxState.value = when (val discovery = Tmux.parseDiscovery(out)) {
            TmuxDiscovery.NotInstalled -> previous.copy(
                phase = TmuxPhase.NOT_INSTALLED,
                sessions = emptyList(),
                busy = false,
                message = null,
            )
            is TmuxDiscovery.Ready -> previous.copy(
                phase = TmuxPhase.READY,
                sessions = discovery.sessions,
                busy = false,
                message = null,
            ).also {
                // 记下远端真的可用的 TERM，供后续 attach 使用（远端缺 xterm-256color 条目时
                // tmux 会拒绝启动，这是 rc.3 附加全挂的根因）。
                discovery.term?.let { t -> ts.negotiatedTerm.value = t }
                sessions.reconcileTmuxAssociations(
                    ts.host.id,
                    discovery.sessions,
                )
            }
            TmuxDiscovery.Malformed -> previous.copy(
                phase = TmuxPhase.ERROR,
                busy = false,
                message = str(R.string.tmux_invalid_response),
            )
        }
    }

    /** 执行管理命令，显示远端错误；成功后在同一个串行临界区内回刷。 */
    private fun tmuxAction(
        ts: TermSession,
        cmd: String,
        onSuccess: () -> Unit = {},
    ) = viewModelScope.launch(Dispatchers.IO) {
        ts.tmuxMutex.withLock {
            val before = ts.tmuxState.value
            ts.tmuxState.value = before.copy(busy = true, message = null)
            val out = runCatching { ts.transport.exec(Tmux.actionCmd(cmd)) }.getOrNull()
            val result = out?.let(Tmux::parseAction)
            when {
                out == null -> ts.tmuxState.value = before.copy(
                    busy = false,
                    message = str(R.string.tmux_control_unavailable),
                )
                result == null -> ts.tmuxState.value = before.copy(
                    busy = false,
                    message = str(R.string.tmux_invalid_response),
                )
                !result.ok -> ts.tmuxState.value = before.copy(
                    busy = false,
                    message = result.output.ifBlank { str(R.string.tmux_action_failed) }.take(500),
                )
                else -> {
                    onSuccess()
                    refreshTmuxLocked(ts, retryUntilReady = false)
                }
            }
        }
    }
    fun tmuxNew(ts: TermSession, name: String) = tmuxAction(ts, Tmux.newCmd(name))
    fun tmuxRename(ts: TermSession, id: String, name: String) = tmuxAction(ts, Tmux.renameCmd(id, name)) {
        sessions.renameTmuxAssociation(ts.host.id, id, name)
    }
    fun tmuxDetach(ts: TermSession, id: String) = tmuxAction(ts, Tmux.detachCmd(id)) {
        sessions.clearTmuxAssociation(ts.host.id, id, tmuxNameOf(ts, id))
    }
    fun tmuxKill(ts: TermSession, id: String) = tmuxAction(ts, Tmux.killCmd(id)) {
        sessions.clearTmuxAssociation(ts.host.id, id, tmuxNameOf(ts, id))
    }

    /** 面板列表里该 ID 对应的会话名：本地关联可能还没拿到 ID，只能按名清（见 clearTmuxAssociation）。 */
    private fun tmuxNameOf(ts: TermSession, id: String): String? =
        ts.tmuxState.value.sessions.firstOrNull { it.id == id }?.name

    /**
     * 打开远端 tmux 会话：创建（或复用）专门的 Moke 终端连接，按名称原子恢复。
     * 不向当前前台终端注入文本，因此当前正在运行的 shell/TUI/半输入命令均不会被破坏。
     */
    fun openTmuxSession(
        source: TermSession,
        target: TmuxSession,
        detachOthers: Boolean = false,
    ): String {
        val session = sessions.openTmux(source, target, resolveJump(source.host), detachOthers)
        ensureSessionService()
        rememberTmuxSession(source.host, target.name)
        confirmTmuxAttach(session, target.name)
        return session.id
    }

    /** 记住该主机上最后选择的 tmux 会话名：下次连接可直接按名附加，不再打扰用户选。 */
    private fun rememberTmuxSession(host: Host, name: String) = viewModelScope.launch {
        store.update(host.id) { if (it.tmuxSessionName == name) it else it.copy(tmuxSessionName = name) }
    }

    /**
     * 附加确认：发出 attach 命令 ≠ 附加成功。远端 tmux 缺失或启动失败时包装命令会回落成登录壳，
     * 此时若仍标成「当前 tmux 会话」，面板与顶栏就在撒谎。用源会话的侧通道数一下客户端：
     * **持续**为 0（一直数到窗口最后一轮）且此刻会话仍存活 → 判定未附上，清除关联。数不出来（null）、
     * 会话已结束、以及窗口内的中间态 0 都视为"无法确认"，不动状态。
     */
    private fun confirmTmuxAttach(session: TermSession, name: String) =
        viewModelScope.launch(Dispatchers.IO) {
            // 用**该会话自己**的侧通道：源会话（临时登录壳）在选定后就被关掉了，拿它去问必然失败。
            // 传输要等 View 测量后才 start，所以给若干次重试；数不出来一律当"无法确认"，不动状态。
            repeat(ATTACH_CONFIRM_ROUNDS) { round ->
                delay(1000)
                if (!session.alive.value) return@launch
                val count = Tmux.parseClientCount(
                    runCatching { session.transport.exec(Tmux.clientsCmd(name)) }.getOrNull()
                ) ?: return@repeat
                // 命令是在会话还活着时发出的，结果却可能在它结束之后才回来。这种时候"0 个客户端"
                // 说明不了任何事——detach 本身就会让计数归零——按它清关联会把「已离开 tmux，远端
                // 会话仍在运行」退化成「会话已结束」（实测：detach 恰好撞上确认往返时就会这样）。
                if (!session.alive.value) return@launch
                if (count > 0) {
                    session.tmuxAttached.value = true
                    // 附上了才下发滚动绑定：让 tmux 现场判定翻页器/命令行（客户端侧判不了，见
                    // Tmux.scrollSetupCmd）。失败无所谓——退回原有的客户端启发式。
                    if (tmuxScrollSetup.value) {
                        runCatching { session.transport.exec(Tmux.scrollSetupCmd(name)) }
                    }
                    return@launch
                }
                // 数到 0 **不等于**没附上：mosh 要先引导 mosh-server、再由包装命令里的 tmux 去附加，
                // 这中间有几秒；第一轮就把 0 当结论，会把一个其实附加成功的会话永久标成"没附上"
                // ——面板于此给出「加入」（再开一个客户端）、detach 退化成「会话已结束」、滚动绑定
                // 也不会下发。所以只有窗口最后一轮的 0 才算数。
                if (round == ATTACH_CONFIRM_ROUNDS - 1) {
                    session.tmuxAttached.value = false
                    session.remoteTmuxId.value = null
                    session.remoteTmuxName.value = null
                }
            }
        }

    /** 重连时保留协议级启动命令；tmux 专用会话不能退回成普通 shell。 */
    fun reconnectSession(source: TermSession): String {
        touchHost(source.host)
        val session = sessions.open(
            host = source.host,
            jumpHost = resolveJump(source.host),
            initialTitle = source.displayTitle.value,
            remoteTmuxId = source.remoteTmuxId.value,
            remoteTmuxName = source.remoteTmuxName.value,
            startupCommand = source.startupCommand,
        )
        ensureSessionService()
        // 重连同样要核对是否真的附上了。漏掉这一步，新会话的 tmuxAttached 恒为 null：
        // detach 后提示会退化成「会话已结束」，而 tmux 真的没附上时顶栏还继续标着 tmux ——
        // 正是首次附加时特意用侧通道消灭掉的那种「UI 撒谎」。
        source.remoteTmuxName.value?.let { confirmTmuxAttach(session, it) }
        return session.id
    }

    /**
     * 记录最近连接时间（用于"最近连接"排序）。
     *
     * **只能按 id 重读当前记录再改这一个字段**：`upsert` 是整条替换，而调用方手里的 [host] 往往是
     * 会话**打开那一刻**的快照（重连/复制会话都拿 `TermSession.host`）。把快照整条写回去，等于把
     * 此后的一切改动静默回滚——实测「会话开着时编辑主机 → 复制会话」编辑即丢，`tmuxSessionName`
     * 也是这样被吃掉的（于是"选择会被记住"失效、选择器反复弹）。
     */
    fun touchHost(host: Host) = viewModelScope.launch {
        store.update(host.id) { it.copy(lastConnectedAt = System.currentTimeMillis()) }
    }

    /**
     * 新建会话并返回其 id（UI 据此导航到终端页），并记录最近连接。解析跳板机（避免自引用）。
     *
     * 会话持久化=tmux 时自适应两条路：
     * - 已记住会话名 → 连接即按名附加（`new-session -A` 原子"存在则附加、否则创建"），零额外往返。
     * - 还没记住 → 先开普通登录壳，连上后侧通道探测，再弹选择器。**不**在连接前另开一条探测连接：
     *   那会多一次握手，还会让探测连接成为 TOFU 的首次信任（指纹静默入库）。
     */
    fun openSession(host: Host): String {
        touchHost(host)
        val remembered = host.tmuxSessionName
            .takeIf { host.persistence == SessionPersistence.TMUX && it.isNotBlank() }
        val ts = sessions.open(
            host = host,
            jumpHost = resolveJump(host),
            remoteTmuxName = remembered,
            startupCommand = remembered?.let { Tmux.attachOrCreateCommand(it) },
        )
        ensureSessionService()
        if (remembered != null) {
            confirmTmuxAttach(ts, remembered)
        } else if (host.persistence == SessionPersistence.TMUX) {
            requestTmuxPickerWhenReady(ts)
        }
        return ts.id
    }

    /** 用户从主机卡片明确选择项目，始终新建干净终端；不更改默认连接/上次 tmux 选择。 */
    fun openProject(host: Host): String {
        val path = host.projectPath.takeIf { it.startsWith('/') } ?: return openSession(host)
        val name = Tmux.projectSessionName(host)
        val ts = sessions.open(
            host = host,
            jumpHost = resolveJump(host),
            initialTitle = "tmux · $name",
            remoteTmuxName = name,
            startupCommand = Tmux.attachOrCreateInPathCommand(name, path),
        )
        touchHost(host)
        ensureSessionService()
        confirmTmuxAttach(ts, name)
        return ts.id
    }

    /** 连接就绪后探测一次；确实装了 tmux 才弹选择器（未安装/失败都不打扰）。 */
    private fun requestTmuxPickerWhenReady(ts: TermSession) = viewModelScope.launch {
        refreshTmux(ts).join()
        if (ts.alive.value && ts.tmuxState.value.phase == TmuxPhase.READY) {
            _tmuxPicker.value = ts.id
        }
    }

    /** 连接时待弹选择器的会话 id（null=不弹）。 */
    private val _tmuxPicker = MutableStateFlow<String?>(null)
    val tmuxPicker: StateFlow<String?> = _tmuxPicker.asStateFlow()

    fun dismissTmuxPicker() { _tmuxPicker.value = null }

    /**
     * 从选择器选定一个会话名（已存在或新建都走同一条路——`new-session -A` 原子处理）。
     * 用独立连接附加，并关掉刚才那条临时登录壳，避免一台主机上挂两条连接。
     */
    fun pickTmuxSession(sourceId: String, name: String): String? {
        val source = sessions.get(sourceId) ?: return null
        _tmuxPicker.value = null
        val target = source.tmuxState.value.sessions.firstOrNull { it.name == name }
            ?: TmuxSession(id = "", name = name, windows = 0, clients = 0, created = 0L)
        val newId = openTmuxSession(source, target)
        if (newId != sourceId) sessions.close(sourceId)
        return newId
    }

    /** 复制会话：用同一主机再开一个独立连接，沿用来源的标题/前缀并加不重复标记。源不存在时返回 null。 */
    fun duplicateSession(id: String): String? {
        val src = sessions.get(id) ?: return null
        touchHost(src.host)
        val newId = sessions.open(src.host, resolveJump(src.host), carryFrom = src).id
        ensureSessionService()
        return newId
    }

    /** 解析主机的跳板机（避免自引用；空/无效返回 null）。 */
    private fun resolveJump(host: Host): Host? = host.jumpHostId
        .takeIf { it.isNotBlank() && it != host.id }
        ?.let { id -> hosts.value.firstOrNull { it.id == id } }

    /** 拉起前台服务：退后台/关屏时保活会话（服务在会话归零时自行停止）。 */
    private fun ensureSessionService() {
        val ctx = getApplication<Application>()
        runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MokeSessionService::class.java)) }
    }

    fun closeSession(id: String) = sessions.close(id)

    /** 一次清掉列表里所有已结束的会话（保留活着的）。 */
    fun closeEndedSessions() {
        sessions.sessions.value.filterNot { it.alive.value }.forEach { sessions.close(it.id) }
    }

    // ---------- 文件（SFTP） ----------

    /** 传输队列：Application 作用域，退后台/关屏由 [MokeTransferService] 保活。 */
    val transfers = (app as MokeApplication).transfers.also { mgr ->
        // 记住的下载目录失效（被删/撤授权）时忘掉它，之后回到默认落点。
        mgr.onTreeUnusable = { viewModelScope.launch { settings.setDownloadTreeUri("") } }
        // DONE 回调在 IO 线程；先记录在 Application 会话中，UI 销毁也不丢路径。
        mgr.onUploadDone = { taskId, hostId, remotePath ->
            val session = sessions.sessions.value.firstOrNull { it.host.id == hostId && it.pendingDraftUploads.remove(taskId) }
            if (session?.alive?.value == true) {
                val quoted = com.briqt.moke.terminal.sftp.RemotePath.shellQuote(remotePath)
                session.composerDraft.update { draft -> if (draft.isBlank()) quoted else "$draft $quoted" }
                session.draftNeedsReview.value = true
            }
            viewModelScope.launch {
                val st = filesState.value
                if (st.host?.id == hostId && st.path == com.briqt.moke.terminal.sftp.RemotePath.parent(remotePath)) {
                    filesController.refresh()
                }
            }
        }
    }

    private val filesController = FilesController(app, viewModelScope)
    val filesState: StateFlow<FilesUiState> = filesController.state

    val downloadTreeUri: StateFlow<String> = settings.downloadTreeUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val filesShowHidden: StateFlow<Boolean> = settings.filesShowHidden
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val filesSort: StateFlow<FilesSort> = settings.filesSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, FilesSort.NAME)

    /** 从主机卡片进入时使用用户保存的目录；终端来源仍以 tmux pane 当前目录优先。 */
    fun openFiles(host: Host, from: TermSession? = null) {
        filesController.open(host, resolveJump(host), from, host.projectPath.takeIf { from == null && it.isNotBlank() })
    }

    private var filesFromSessionId: String? = null

    fun setFilesFromSession(sessionId: String?) { filesFromSessionId = sessionId }

    fun closeFiles() {
        filesFromSessionId = null
        _uploadConflict.value = null
        filesController.close()
    }
    fun filesNavigate(path: String) = filesController.navigate(path)
    fun filesUp() = filesController.up()
    fun filesRefresh() = filesController.refresh()
    fun filesGoto(path: String) = filesController.goto(path)
    fun filesMkdir(name: String) = filesController.mkdir(name)
    fun filesClearError() = filesController.clearError()
    fun setFilesSort(s: FilesSort) = viewModelScope.launch { settings.setFilesSort(s) }
    fun setFilesShowHidden(on: Boolean) = viewModelScope.launch { settings.setFilesShowHidden(on) }

    /** 记住下载目录（已在 UI 侧取得持久化读写授权）。 */
    fun setDownloadTree(uri: String) = viewModelScope.launch { settings.setDownloadTreeUri(uri) }

    /** 待确认覆盖的上传（非空=显示确认弹窗）。 */
    private val _uploadConflict = MutableStateFlow<UploadConflict?>(null)
    val uploadConflict: StateFlow<UploadConflict?> = _uploadConflict.asStateFlow()

    /** 同名覆盖确认绑定主机、目录和来源会话；期间切走目录不能把文件传到错误位置。 */
    fun uploadHere(uris: List<android.net.Uri>) = viewModelScope.launch {
        val state = filesState.value
        val host = state.host ?: return@launch
        val dir = state.path.takeIf { it.isNotBlank() } ?: return@launch
        val source = filesFromSessionId
        val names = uris.map { displayNameOfUri(it) }
        val clash = filesController.existingNames(names)
        if (filesState.value.host?.id != host.id || filesState.value.path != dir) return@launch
        if (clash.isEmpty()) startUpload(uris, host, dir, source)
        else _uploadConflict.value = UploadConflict(uris, clash, host.id, dir, source)
    }

    fun confirmUploadOverwrite() {
        val pending = _uploadConflict.value ?: return
        _uploadConflict.value = null
        val state = filesState.value
        if (state.host?.id != pending.hostId || state.path != pending.dir) return
        startUpload(pending.uris, state.host, state.path, pending.sessionId)
    }

    fun dismissUploadConflict() { _uploadConflict.value = null }

    private fun startUpload(uris: List<android.net.Uri>, host: Host, dir: String, source: String?) {
        transfers.enqueueUpload(host, uris, dir, onQueued = { ids ->
            source?.let { sessions.get(it) }?.takeIf { it.alive.value && it.host.id == host.id }
                ?.pendingDraftUploads?.addAll(ids)
        })
        ensureTransferService()
    }

    /** SAF URI 的显示名（拿不到就退回最后一段路径，与传输层同一口径）。 */
    private fun displayNameOfUri(uri: android.net.Uri): String {
        val ctx = getApplication<Application>()
        val fromCursor = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
        }.getOrNull()
        return fromCursor ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /**
     * 是否还得先问用户要一个下载目录。
     *
     * Android 10+ 有免权限写系统「下载」的通道，默认落 `下载/Moke`，一次都不问；更低版本没有
     * 这条路，只能让用户选一个目录（选完记住）。
     */
    val needsDownloadDir: StateFlow<Boolean> = downloadTreeUri
        .map { it.isBlank() && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** [treeUri] 为 null=用默认落点（下载/Moke）。 */
    fun download(entry: com.briqt.moke.terminal.sftp.RemoteEntry, treeUri: android.net.Uri?) {
        val host = filesState.value.host ?: return
        transfers.enqueueDownload(host, entry, treeUri)
        ensureTransferService()
    }

    fun resumeTransfer(id: String) {
        transfers.retry(id)
        ensureTransferService()
    }

    fun cancelTransfer(id: String) = transfers.cancel(id)
    fun removeTransfer(id: String) {
        sessions.sessions.value.forEach { it.pendingDraftUploads.remove(id) }
        transfers.remove(id)
    }
    fun clearFinishedTransfers() = transfers.clearFinished()

    /** 把用户选中的现有远端路径写入终端输入行（已 shell 转义）。 */
    fun sendToTerminal(sessionId: String, text: String) {
        sessions.get(sessionId)?.session?.write(text)
    }

    private fun ensureTransferService() {
        val ctx = getApplication<Application>()
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, MokeTransferService::class.java))
        }
    }

    fun setColorScheme(id: String) = viewModelScope.launch { settings.setColorScheme(id) }

    fun setLightColorScheme(id: String) = viewModelScope.launch { settings.setLightColorScheme(id) }

    fun setSchemeFollowsTheme(on: Boolean) = viewModelScope.launch { settings.setSchemeFollowsTheme(on) }

    fun setFontSize(sp: Float) = viewModelScope.launch { settings.setFontSize(sp) }

    fun setCursorStyle(style: Int) = viewModelScope.launch { settings.setCursorStyle(style) }

    fun setCursorBlink(blink: Boolean) = viewModelScope.launch { settings.setCursorBlink(blink) }

    fun setLineSpacing(v: Float) = viewModelScope.launch { settings.setLineSpacing(v) }

    fun setLetterSpacing(v: Float) = viewModelScope.launch { settings.setLetterSpacing(v) }

    fun setExtraKeysVisible(visible: Boolean) = viewModelScope.launch { settings.setExtraKeysVisible(visible) }
    fun setExtraKeysLayout(layout: ExtraKeysLayout) = viewModelScope.launch { settings.setExtraKeysLayout(layout) }

    fun setCustomExtraKeyIds(ids: List<String>) = viewModelScope.launch {
        settings.setCustomExtraKeyIds(validCustomKeyIds(ids))
    }

    fun setThemeMode(m: ThemeMode) = viewModelScope.launch { settings.setThemeMode(m) }

    fun setDynamicColor(on: Boolean) = viewModelScope.launch { settings.setDynamicColor(on) }

    fun setKeyboardMode(m: KeyboardMode) = viewModelScope.launch { settings.setKeyboardMode(m) }
    fun setScrollMode(m: ScrollMode) = viewModelScope.launch { settings.setScrollMode(m) }

    fun setTmuxScrollSetup(on: Boolean) = viewModelScope.launch { settings.setTmuxScrollSetup(on) }

    fun setConfirmCloseSession(on: Boolean) = viewModelScope.launch { settings.setConfirmCloseSession(on) }

    fun setAutoTrustNewHostKey(on: Boolean) = viewModelScope.launch { settings.setAutoTrustNewHostKey(on) }

    /** 「响铃与通知提醒」（默认关闭）；镜像到 TerminalAlerts.enabled 由 MokeApplication 维护。 */
    val terminalAlerts: StateFlow<Boolean> = settings.terminalAlerts
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setTerminalAlerts(on: Boolean) = viewModelScope.launch { settings.setTerminalAlerts(on) }

    fun setKeepScreenOn(on: Boolean) = viewModelScope.launch { settings.setKeepScreenOn(on) }

    /** 恢复外观默认（配色/字号/行距/字距/光标）。 */
    fun resetAppearanceDefaults() = viewModelScope.launch { settings.resetAppearanceDefaults() }

    // 放在类末尾：init 里要用到上面声明的 settings / appVersion，Kotlin 按声明顺序初始化，提前放会 NPE。
    init {
        repairLegacyTmuxLoginCommands()
    }

    private companion object {
        /**
         * 附加确认的轮数（每轮间隔 1s）：要盖住"mosh 引导 + tmux 附加"的那几秒，又不至于让真的
         * 没附上的会话长时间挂着错误标注。
         */
        const val ATTACH_CONFIRM_ROUNDS = 8
        const val MAX_HOST_MIGRATION_BYTES = 8 * 1024 * 1024
    }
}
