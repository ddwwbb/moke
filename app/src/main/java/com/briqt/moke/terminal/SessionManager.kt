package com.briqt.moke.terminal

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.data.Host
import com.briqt.moke.terminal.sftp.RemotePath
import com.briqt.moke.localized
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/**
 * 一个活动终端会话。传输 / emulator / 滚屏历史都在 [session] 内，**跨页面存活**；
 * 终端页每次进入时重建 TerminalView 并 attach 到既有 [session]——因 TerminalView.updateSize
 * 对已存在的 emulator 只 resize 不重建，滚屏与连接得以保留（见 terminal-view 分析）。
 */
class TermSession(
    val id: String,
    val host: Host,
    val controller: TerminalController,
    val session: TerminalSession,
    /** 底层传输（用于 tmux 侧通道 exec 等带外能力）。 */
    val transport: TerminalTransport,
    /** 配置式 ProxyJump；null 表示直连。只描述应用知道的连接路由。 */
    val jumpHost: Host?,
    /** 协议级启动的交互程序；tmux 使用它避开 shell 提示符注入。 */
    val startupCommand: String?,
    /** 原生动态标题（转义序列设置，缺省用连接名）。展示请用 [displayTitle]。 */
    val title: StateFlow<String>,
    /** 标题基座（`user@host` 或 tmux 会话名）：动态标题缺失/被清空时回落到它，保证标题行永不空白。 */
    val baseTitle: String,
    /** 用户自定义标题：非空则优先级最高，完全覆盖动态标题。 */
    val customTitle: MutableStateFlow<String?>,
    private val displayTitleState: MutableStateFlow<String>,
    /** 传输是否仍存活（false = 会话已结束）。 */
    val alive: StateFlow<Boolean>,
    /** 实时网络往返延迟（ms，null=未知/不适用）。 */
    val latency: StateFlow<Int?>,
    /**
     * 复制会话的消歧标记（如 "(2)"）；null=非复制。
     * 它不是标题内容：仅在同主机实际展示标题冲突时临时出现，自定义标题时永不强加。
     */
    val copyMark: String? = null,
    /** 当前远端关联；显式 detach/删除或远端消失后清空，网络断开不清空。 */
    val remoteTmuxId: MutableStateFlow<String?>,
    /** tmux 跨连接恢复身份；ID 随 server 重启变化时用名称重新收敛。 */
    val remoteTmuxName: MutableStateFlow<String?>,
    /** 独立的手动恢复目标；断线、显式分离或附加失败不丢失，恢复时仍须校验远端存在。 */
    val tmuxRecovery: MutableStateFlow<TmuxRecovery?>,
    /** OSC 7 上报的 shell 当前目录；远端未上报时为 null。 */
    val lastReportedCwd: MutableStateFlow<String?>,
    val startedAt: Long,
) {
    /** 最终展示标题：customTitle 优先；复制标记仅作临时冲突消歧。 */
    val displayTitle: StateFlow<String> = displayTitleState.asStateFlow()

    /** 最后活动时间（有终端输出即刷新）：用于「更新时间」排序。非响应式（普通 volatile），列表重组时读当前值即可，避免高频重排抖动。 */
    @Volatile var lastActivityAt: Long = startedAt
    /** 文本段草稿随终端会话存活；离开文件页或切换终端 View 不丢失。 */
    val composerDraft = MutableStateFlow("")
    val draftNeedsReview = MutableStateFlow(false)
    /** 仅本进程有效：上传任务完成后，属于本终端的路径才可进入草稿。 */
    val pendingDraftUploads: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    /** tmux 管理完整状态；明确区分检查中、零会话、未安装与失败。 */
    val tmuxState: MutableStateFlow<TmuxUiState> = MutableStateFlow(TmuxUiState())
    val tmuxMutex = Mutex()

    /**
     * 远端协商出的可用 TERM（[Tmux.DISCOVER_CMD] 的产物）；null=还没探测/远端无判定工具。
     * 附加 tmux 时必须用它，否则远端缺 `xterm-256color` 条目时 tmux 会拒绝启动。
     */
    val negotiatedTerm: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * 本终端是否**确实**附加在 [remoteTmuxName] 上（侧通道核对客户端数的结果）。
     * null=尚未确认；false=确认未附上（tmux 缺失/启动失败，已回落登录壳）。
     * 光有 startupCommand 不能当作附加成功，否则 UI 会撒谎。
     */
    val tmuxAttached: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    /** 用户通过管理面板结束交互的原因；不与附加失败混用，重连时重新确认。 */
    val tmuxEndReason = MutableStateFlow<TmuxEndReason?>(null)

    /**
     * 本会话是**连不上**而结束的（DNS / 网络 / 认证 / 主机密钥被拒），而不是连上后正常退出。
     * 结束条据此说「连接失败」并给出「编辑主机」——两种结束给同一句「会话已结束」，用户分不清该干什么。
     */
    val connectFailed = MutableStateFlow(false)

    /** 本会话上的本地端口转发（SSH 借会话连接，mosh 借控制连接）。 */
    val forwards: PortForwards? = (transport as? ForwardCapable)?.let { PortForwards(it) }

    /** 设自定义标题（空白视为清除，回落到动态标题）。 */
    fun setCustomTitle(t: String?) { customTitle.value = t?.trim()?.ifBlank { null } }

    internal fun updateDisplayTitle(title: String) {
        displayTitleState.value = title
    }

    companion object {
        // mosh-client 原生给窗口标题加固定前缀 "[mosh] "（mosh 1.4.0 stmclient.cc: L"[mosh] "，仅一个空格、无点）；
        // 协议已由徽标标识，展示时去掉它。防御性地允许重复与多余空白/点。
        private val MOSH_PREFIX = Regex("^(?:\\[mosh][\\s.·]*)+", RegexOption.IGNORE_CASE)

        /**
         * 组合标题本体；复制标记由 SessionManager 根据实时冲突另行派生，不写进标题本体。
         *
         * [base] 是兜底：mosh 会给窗口标题加 `[mosh] ` 前缀，而远端程序退出时常发一条**空 OSC**
         * 重置标题（mosh 对任何 OSC 都置 title_initialized，于是把 `[mosh] ` 单独发过来）——
         * 剥掉前缀后就只剩空串，标题行会整条空白。剥完为空即回落基座。
         */
        fun composeTitle(useMosh: Boolean, raw: String, custom: String?, base: String): String {
            custom?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val stripped = if (useMosh) raw.replaceFirst(MOSH_PREFIX, "") else raw
            return stripped.trim().ifEmpty { base }
        }

        fun disambiguateTitle(base: String, custom: String?, mark: String?, hasCollision: Boolean): String =
            if (custom.isNullOrBlank() && hasCollision && !mark.isNullOrBlank()) "$base $mark" else base
    }
}

/**
 * 多会话管理器（总纲 §5.6「ViewModel 持有会话列表」）。会话对象常驻 ViewModel，不随导航销毁，
 * 是"多会话"卖点的地基。cold start 无持久化（后台保活为后续里程碑），故重启后列表为空。
 */
class SessionManager(context: Context) {

    private val appContext = context.applicationContext
    // 常驻作用域：监听动态/自定义标题并实时重新计算冲突消歧（与整个 app 同生命周期）。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val sessions: StateFlow<List<TermSession>> = _sessions.asStateFlow()

    /**
     * 为主机新建一个会话（传输在首次 attach 到已测量的 View 时才真正 start）。[jumpHost] 为已解析的跳板机。
     *
     * 标题：动态标题基座=`user@host`，shell 上报 OSC 标题后被替换（展示在标题行第 1 行）；
     * 连接名（设备名）由副标题第 2 行固定展示。无前缀概念。
     *
     * 复制（[carryFrom] 非空）：沿用来源当前标题与自定义标题，并生成同主机内不重复的标记。
     * 标记只在两个实际标题仍冲突时显示；标题自然分化或用户手动命名后自动消失。
     */
    fun open(
        host: Host,
        jumpHost: Host? = null,
        carryFrom: TermSession? = null,
        initialTitle: String? = null,
        remoteTmuxId: String? = null,
        remoteTmuxName: String? = null,
        startupCommand: String? = null,
        tmuxRecovery: TmuxRecovery? = remoteTmuxName?.let { TmuxRecovery(it) },
        replaceFrom: TermSession? = null,
    ): TermSession {
        val baseTitle = baseTitleOf(host)
        val initialCustom = (replaceFrom ?: carryFrom)?.customTitle?.value
        val mark = replaceFrom?.copyMark ?: if (carryFrom != null) nextCopyMark(host.id) else null

        // 基座 = 会话固有身份（tmux 会话名或 user@host）：动态标题被远端清空时回落到它。
        val titleBase = initialTitle ?: baseTitle
        val title = MutableStateFlow(initialTitle ?: carryFrom?.title?.value ?: baseTitle)
        val customTitle = MutableStateFlow(initialCustom)
        val initialDisplay = TermSession.composeTitle(host.useMosh, title.value, initialCustom, titleBase)
        val displayTitle = MutableStateFlow(initialDisplay)
        val alive = MutableStateFlow(true)
        val latency = MutableStateFlow<Int?>(null)
        val lastReportedCwd = MutableStateFlow<String?>(null)
        val controller = TerminalController(
            context = appContext,
            onFinished = { alive.value = false; latency.value = null },
            // 空标题（远端程序退出时常发的空 OSC）当作「清除」处理，回落基座；
            // 早期实现直接忽略，结果标题一直挂着上一个程序的名字。
            onTitle = { t -> title.value = if (t.isNullOrBlank()) titleBase else t },
            // 不把嵌套 SSH 的 cwd 拿到原主机执行 Git；失配时清除上一次报告。
            onCwd = { reportedHost, cwd ->
                lastReportedCwd.value = cwd.takeIf { reportedHost.isNotEmpty() && reportedHost.equals(host.host, ignoreCase = true) }
            },
        )
        // 传输选择：偏好 mosh 的主机走 MoshTransport（SSH 引导 + native mosh-client 子进程 PTY），
        // 否则走 SshTransport（并周期探测 RTT 供状态条显示）。
        val transport = if (host.useMosh) {
            MoshTransport(host, appContext, jumpHost, startupCommand)
        } else {
            SshTransport(
                host = host,
                context = appContext,
                jumpHost = jumpHost,
                onLatency = { latency.value = it },
                startupCommand = startupCommand,
            )
        }
        val session = TerminalSession(transport, 2000, controller)
        val ts = TermSession(
            id = UUID.randomUUID().toString(),
            host = host,
            controller = controller,
            session = session,
            transport = transport,
            jumpHost = jumpHost,
            startupCommand = startupCommand,
            title = title.asStateFlow(),
            baseTitle = titleBase,
            customTitle = customTitle,
            displayTitleState = displayTitle,
            alive = alive.asStateFlow(),
            latency = latency.asStateFlow(),
            copyMark = mark,
            remoteTmuxId = MutableStateFlow(remoteTmuxId),
            remoteTmuxName = MutableStateFlow(remoteTmuxName),
            tmuxRecovery = MutableStateFlow(tmuxRecovery),
            lastReportedCwd = lastReportedCwd,
            startedAt = System.currentTimeMillis(),
        )
        // 结束文案按本会话的真实处境说：确认附加在 tmux 上、又是正常退出（detach 就是 code 0），
        // 屏幕上写「会话结束」会和界面上「已离开 tmux，远端会话仍在运行」自相矛盾。
        session.sessionStatusText = object : TerminalSession.StatusText {
            override fun connectFailed(reason: String): String {
                ts.connectFailed.value = true
                return TerminalSession.statusText.connectFailed(reason)
            }

            override fun sessionEnded(exitCode: Int): String =
                if (exitCode == 0 && (ts.tmuxEndReason.value == TmuxEndReason.DETACHED ||
                        (ts.tmuxEndReason.value == null && ts.tmuxAttached.value == true))) {
                    appContext.localized(R.string.term_left_tmux)
                } else {
                    TerminalSession.statusText.sessionEnded(exitCode)
                }
        }
        // 主机级"连接后自动转发的端口"：连上之后在后台建好（mosh 要现建控制连接，不能占传输线程）。
        val autoPorts = PortForwards.parsePorts(host.forwardPorts).ports
        if (autoPorts.isNotEmpty()) {
            val onUp: () -> Unit = { scope.launch(Dispatchers.IO) { autoPorts.forEach { ts.forwards?.start(it) } } }
            when (transport) {
                is SshTransport -> transport.onEstablished = onUp
                is MoshTransport -> transport.onEstablished = onUp
            }
        }
        // 响铃 / 通知序列 → 用户不在这个会话里时发系统通知（开关默认关闭，见 TerminalAlerts）。
        controller.onAlert = { t, b -> TerminalAlerts.post(appContext, ts.id, ts.displayTitle.value, t, b) }
        // 有输出即刷新会话最后活动时间（供"更新时间"排序）。
        controller.onActivity = { ts.lastActivityAt = System.currentTimeMillis() }
        replaceFrom?.let { source ->
            ts.composerDraft.value = source.composerDraft.value
            ts.draftNeedsReview.value = source.draftNeedsReview.value
            ts.negotiatedTerm.value = source.negotiatedTerm.value
            synchronized(source.pendingDraftUploads) {
                ts.pendingDraftUploads.addAll(source.pendingDraftUploads)
                source.pendingDraftUploads.clear()
            }
        }
        _sessions.update { list ->
            if (replaceFrom != null && list.any { it.id == replaceFrom.id }) {
                list.map { if (it.id == replaceFrom.id) ts else it }
            } else list + ts
        }
        replaceFrom?.let(::finish)
        combine(title, customTitle) { _, _ -> Unit }
            .onEach { refreshDisplayTitles() }
            .launchIn(scope)
        refreshDisplayTitles()
        return ts
    }

    /** 替换传输，但保留用户会话状态与列表位置；tmux 身份只来自结构化恢复目标。 */
    fun reconnect(source: TermSession, jumpHost: Host? = source.jumpHost): TermSession {
        val target = source.tmuxRecovery.value
        return open(
            host = source.host,
            jumpHost = jumpHost,
            initialTitle = source.title.value,
            remoteTmuxName = target?.name,
            startupCommand = target?.let { Tmux.attachExistingCommand(it, source.negotiatedTerm.value) }
                ?: source.startupCommand,
            tmuxRecovery = target,
            replaceFrom = source,
        )
    }

    /** 上传在 IO 完成；在 Application 主线程作用域归属到会话，与重连替换串行化。 */
    fun onUploadDone(taskId: String, hostId: String, remotePath: String) {
        scope.launch {
            val session = _sessions.value.firstOrNull {
                it.host.id == hostId && it.pendingDraftUploads.remove(taskId)
            } ?: return@launch
            if (!session.alive.value) return@launch
            val quoted = RemotePath.shellQuote(remotePath)
            session.composerDraft.update { draft -> if (draft.isBlank()) quoted else "$draft $quoted" }
            session.draftNeedsReview.value = true
        }
    }

    /**
     * 在新的干净终端连接中恢复远端 tmux；同一主机同名 tmux 已有活会话时直接复用。
     * 这避免向任意前台程序/半输入命令盲注入 `tmux attach`，也杜绝 tmux 内再嵌套 tmux。
     * 启动时按用户可识别的名称原子 attach-or-create，不把刷新时拿到的临时 `$N` ID 带到新连接。
     */
    fun openTmux(
        source: TermSession,
        target: TmuxSession,
        jumpHost: Host? = null,
        detachOthers: Boolean = false,
        term: String? = null,
    ): TermSession {
        if (!detachOthers) {
            _sessions.value.firstOrNull {
                it.host.id == source.host.id &&
                    (it.remoteTmuxName.value == target.name || it.remoteTmuxId.value == target.id) &&
                    it.alive.value
            }?.let { return it }
        }

        return open(
            host = source.host,
            jumpHost = jumpHost,
            initialTitle = "tmux · ${target.name}",
            // 选择器「新建」走同一条路（`new-session -A` 原子创建），此时还没有远端 ID；
            // 空串不能当成合法 ID，否则关闭确认等按 ID 判断的分支会误判。
            remoteTmuxId = target.id.takeIf { it.isNotBlank() },
            remoteTmuxName = target.name,
            startupCommand = Tmux.attachOrCreateCommand(
                target.name,
                detachOthers,
                term ?: source.negotiatedTerm.value,
            ),
        ).also { it.negotiatedTerm.value = term ?: source.negotiatedTerm.value }
    }

    /** 根据实时标题冲突派生复制标记；用户自定义标题具有绝对优先级。 */
    private fun refreshDisplayTitles() {
        val list = _sessions.value
        val baseById = list.associate { ts ->
            ts.id to TermSession.composeTitle(ts.host.useMosh, ts.title.value, ts.customTitle.value, ts.baseTitle)
        }
        val collisionCount = list.groupingBy { ts ->
            ts.host.id to baseById.getValue(ts.id)
        }.eachCount()

        list.forEach { ts ->
            val base = baseById.getValue(ts.id)
            val collides = collisionCount.getValue(ts.host.id to base) > 1
            ts.updateDisplayTitle(
                TermSession.disambiguateTitle(base, ts.customTitle.value, ts.copyMark, collides)
            )
        }
    }

    /**
     * 显式分离/关闭远端 tmux 后不再标成“当前”；独立恢复目标仍供用户手动重连使用。
     *
     * 名称也要参与匹配：从选择器「新建」出来的终端在第一次刷新之前 `remoteTmuxId` 还是 null，
     * 只按 ID 清会漏掉它——于是面板已经把会话 detach 掉了，顶栏还挂着「当前 tmux 会话」。
     * 同一主机上 tmux 会话名是唯一的，按名匹配不会误伤别的会话。
     */
    fun clearTmuxAssociation(
        hostId: String,
        remoteId: String,
        remoteName: String? = null,
        reason: TmuxEndReason,
    ) {
        _sessions.value
            .filter {
                it.host.id == hostId &&
                    (it.remoteTmuxId.value == remoteId ||
                        (remoteName != null && it.remoteTmuxName.value == remoteName))
            }
            .forEach {
                it.tmuxEndReason.value = reason
                it.remoteTmuxId.value = null
                it.remoteTmuxName.value = null
                it.tmuxAttached.value = false
            }
    }

    /** 外部重启 server / 重命名 / 删除后，以名称优先、ID 兜底更新或清理本地关联。 */
    fun reconcileTmuxAssociations(hostId: String, remoteSessions: List<TmuxSession>) {
        _sessions.value
            .filter { it.host.id == hostId && it.remoteTmuxName.value != null }
            .forEach { local ->
                val match = Tmux.resolveAssociation(
                    // 断线后 server 可能重启并复用 ID；不能把恢复目标改成别的内容。
                    local.remoteTmuxId.value.takeIf { local.alive.value && local.tmuxAttached.value == true },
                    local.tmuxRecovery.value?.name,
                    remoteSessions,
                )
                if (match == null) {
                    local.remoteTmuxId.value = null
                    local.remoteTmuxName.value = null
                } else {
                    local.remoteTmuxId.value = match.id
                    local.remoteTmuxName.value = match.name
                    local.tmuxRecovery.value = local.tmuxRecovery.value?.copy(name = match.name)
                }
            }
    }

    /** 面板重命名成功后同步所有指向该精确远端会话的本地终端。 */
    fun renameTmuxAssociation(hostId: String, remoteId: String, newName: String) {
        _sessions.value
            .filter {
                it.host.id == hostId && it.remoteTmuxId.value == remoteId &&
                    it.alive.value && it.tmuxAttached.value == true
            }
            .forEach {
                it.remoteTmuxName.value = newName
                it.tmuxRecovery.value = it.tmuxRecovery.value?.copy(name = newName)
            }
    }

    /** 动态标题基座（OSC 上报前）：优先 `user@host`；缺 host 回落 displayName、缺 user 只用 host。 */
    private fun baseTitleOf(host: Host): String = when {
        host.host.isBlank() -> host.displayName
        host.username.isBlank() -> host.host
        else -> "${host.username}@${host.host}"
    }

    /** 复制会话标记 "(n)"：在同一主机现有会话已用标记中取未占用的最小 n≥2（未标记会话隐含为 1）。 */
    private fun nextCopyMark(hostId: String): String {
        val used = _sessions.value
            .filter { it.host.id == hostId }
            .mapNotNull { it.copyMark?.trim()?.removeSurrounding("(", ")")?.toIntOrNull() }
            .toSet()
        var n = 2
        while (n in used) n++
        return "($n)"
    }

    fun get(id: String): TermSession? = _sessions.value.firstOrNull { it.id == id }

    /** 拖动重排：按给定的 id 顺序重排会话列表（仅内存）。未知 id 忽略、缺失的追加在末尾。 */
    fun reorder(orderedIds: List<String>) {
        _sessions.update { list ->
            val byId = list.associateBy { it.id }
            val front = orderedIds.mapNotNull { byId[it] }
            val rest = list.filter { it.id !in orderedIds }
            (front + rest).takeIf { it.size == list.size } ?: list
        }
    }

    /** 关闭并从列表移除（关传输幂等）。 */
    fun close(id: String) {
        val ts = get(id) ?: return
        finish(ts)
        _sessions.update { list -> list.filterNot { it.id == id } }
        refreshDisplayTitles()
    }

    private fun finish(ts: TermSession) {
        ts.forwards?.let { f -> scope.launch(Dispatchers.IO) { f.stopAll() } }
        TerminalAlerts.cancel(appContext, ts.id)
        runCatching { ts.session.finishIfRunning() }
    }
}
