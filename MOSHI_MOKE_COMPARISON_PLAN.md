## Context

目标：根据 Moshi 官方文档 https://getmoshi.app/docs 和 Moke 当前源码，交付一份中文竞品对比及按价值、成本和产品边界排序的优化路线图，并将完整分析落到仓库文档 `docs/moshi-moke-comparison.md`；不是现在实现任何产品功能。Moke 定位为 Android 纯客户端，不要求安装专属远端守护程序；可在用户明确触发时通过现有 SSH 连接执行远端只读命令。Moshi 的官方说明只能视为文档声明，Moke 的代码结论也不能冒充真机实测。此次增量评审还要主动寻找原方案未覆盖、但可在该产品边界内借鉴的高价值能力，删除与 Moke 已有实现重复或依赖 Moshi 云端/守护进程才能成立的伪差距。

## Approach

1. 新建 `docs/moshi-moke-comparison.md`，标题固定为 `# Moshi 与 Moke 竞品对比及优化路线图`，二级章节依次固定为：`证据口径`、`决策摘要`、`三层能力边界`、`功能对照`、`原路线图复核`、`新增高价值机会`、`分级路线图`、`明确不建议近期复制`、`未来验收场景`、`核查声明`。`证据口径` 用三条标签约束全文：`官方文档声明` 仅表示 Moshi 页面描述，`源码已见` 仅表示本次读到 Moke 实现，`待真机验证` 表示输入法、手势、进程死亡、SAF/传输或 Android 平台行为未运行。`决策摘要` 明确：Moke 已具备 SSH/Mosh、tmux、SFTP、端口转发和移动终端基础，最高价值不是复制 Moshi 的 hook/cloud 功能，而是缩短附件、工作区恢复、项目入口和诊断/迁移等纯客户端路径。
2. 用 `transport`、`durable workspace`、`agent integration` 三层模型解释能力边界。引用 [Moshi terminal sessions](https://getmoshi.app/docs/terminal-sessions)，并以 `MokeApplication.sessions`、`SessionManager`、`MokeViewModel.openSession/reconnectSession`、`Host.persistence/tmuxSessionName` 为 Moke 证据：Mosh 处理网络漫游，tmux 保远端进程，结构化审批/转录需要 agent 事件源；Moke 的 Application 级会话只在进程内存活，cold start 不恢复旧 `TermSession`、导航或 emulator scrollback，但保存的 host 可再次按名附加 tmux。不得把这三层互相替代。
3. 制作对照表，行固定为：移动连接与重连、tmux 会话与窗口导航、输入/剪贴板/滚屏搜索、附件与 SFTP、项目/工作目录、端口转发预览、连接诊断与首次配置、安全与迁移、AI 专项能力、平台/付费限制。每行同时写 Moshi 的直接专题链接和前提、Moke 实际读取的路径/符号、真实差距、产品边界。明确下列能力已经存在，只能列为可发现性或设备验收：会话分组/排序/拖动（`HomeScreen`、`ListOrder`）、滚屏模式与回到底部（`TerminalScreen`、`MokeScroll`）、额外键和文本段草稿（`ExtraKeys.TextBlockComposer`）、通知按会话跳转（`MainActivity.ACTION_OPEN_SESSIONS`、`TerminalAlerts.post`）、基础 RTT/失败状态、SFTP 断点逻辑、localhost 链接一键 `PortForwards.start` 和 `ForwardPanel` 手动添加/重试。
4. 单列“原路线图复核”，保留原有八项并纠正优先级：保留附件上传成功后插入路径、进程死亡后同名 tmux 恢复、传输设备验收、端口预览可发现性、按需 git diff；协议增量仍需用户需求证据；语音输入及其 Android 现场测试暂缓至未来；agent inbox/审批/Chat View/usage/远程推送/Live Activity 仍不进入纯客户端近期路线。说明现有 `TerminalAlerts` 的 BEL/OSC 9/777 不是 Moshi hook 的结构化审批事件。
5. 在路线图中新增并明确排序以下机会，每项都写用户场景、可复用入口、限制、未来验收和相对投入：
   - **A：显式项目/工作区入口（中）**。允许用户保存远端项目路径，并从该路径打开文件页或创建/附加 tmux 工作区；复用 `Host.group/startupCommand/loginCommand`、`FilesController.probeTerminalPath`、`Tmux.attachOrCreateCommand`，但“项目路径”与启动命令、tmux 会话名分开表述。优先保存用户明确选择的路径；任何代理转录/最近目录读取只能由用户主动触发并走既有 SSH 只读通道，不后台扫描。自动生成 tmux 名称时必须处理不同目录同 basename 冲突，不能附加到错误路径。
   - **A：可移植的主机配置导出/导入（中）**。指出 `Host.toJson/fromJson` 是内部存储格式而非公开迁移契约，`HostStore` 的密文依赖本机 Android Keystore，且 `backup_rules.xml`/`data_extraction_rules.xml` 明确排除主机数据库。建议版本化、可预览的导出文件默认只含非秘密配置（label、group、host、port、username、protocol、jump-host 关系、startup/login、tmux/forward 设置），密码、私钥和 passphrase 默认不导出；导入冲突必须逐条预览并由用户决定新增/覆盖，解析失败定位到具体条目，不静默覆盖现有列表。
   - **B：tmux 会话/窗口按需跳转（中）**。Moke 已有会话层 `Tmux.DISCOVER_CMD` 和 `TmuxPanel`，新增价值是用户点开时只读列出 session/window、标出当前位置并切换窗口；不依赖常驻 daemon、不声称实时 agent 状态。普通 shell、tmux 不可用、窗口已消失或侧通道失败时明确提示；不把 Moshi 外部 deep link 当成 cold-start 恢复能力。
   - **B：用户触发的分阶段连接诊断（中）**。复用 `SshConnector` 的主机密钥校验、跳板链路、认证和现有 `connectFailed/latency`，按地址解析、TCP/SSH 握手、host-key、认证、跳板、Mosh bootstrap/UDP 前提分段展示已观测结果和下一步；仅用户触发，不后台轮询，不绕过 TOFU，不内置 Tailscale 控制面。Tailscale/系统 VPN 只作为普通网络可达性兼容场景。
   - **B：本地 scrollback 搜索与历史分层（中）**。客户端搜索只覆盖当前 `TerminalBuffer` 中现存文本，并说明 Mosh/SSH 重连前历史不可恢复；tmux 的长期历史引导使用 copy-mode 搜索。若文档建议 `tmux capture-pane`，必须限定用户选择明确 pane、限制输出量并标注为按需只读，不把它描述成普通 shell 历史恢复。
   - **B：主机列表搜索/快速筛选（低）**。在大量 host 场景按 label、host、username、group 过滤，保留现有分组、折叠和手动排序语义；这是列表可发现性优化，不是补“分组功能”。Quick Connect 只作为次级入口：仍走 TOFU 与现有认证流程，默认不保存凭据，除非用户明确保存。
   - **C：语音生成可编辑草稿（低至中，暂缓至未来）**。保留为未来方向，但不纳入近期开发；未来启动前，先验证 Android IME 自带语音输入能否在 `KeyboardMode.IME` 与 `TextBlockComposer` 中可靠生成中文、标点、换行和长文本。如需专用入口，使用 Android 平台能力把结果放入草稿，默认不自动发送或追加 Enter。不要复制 Apple SpeechAnalyzer、Moshi Cloud 配额或未经证实的 Android 离线模型声明。
   - **C：本地隐私锁（中）**。区分现有 Keystore 静态加密与恢复应用时的 UI 锁；评估 Android `BiometricPrompt`/设备凭据用于回到前台或使用私钥前确认，但不得中断已在后台运行的会话/传输，也不借锁屏状态泄露通知敏感正文。无硬件、未录入、认证取消和系统锁定都要有明确行为后再进入实现路线。
   - **C：手势自定义只做证据驱动的小步扩展（中）**。现有捏合缩放、滚屏、文字选择、额外键已占用关键触摸语义；先真机验证 TalkBack、系统返回手势、鼠标上报和 TUI 冲突，只考虑可发现、可关闭、可恢复默认的少数高频动作，不直接复制 Moshi 的全量手势映射。
6. 在“明确不建议近期复制”中写清：Moshi [hooks](https://getmoshi.app/docs/hooks)、[Chat View](https://getmoshi.app/docs/chat-view)、[Diff viewer](https://getmoshi.app/docs/diff-viewer)、[Browser preview](https://getmoshi.app/docs/browser-preview)、Agents & Usages、统一推送和 Apple 平台功能依赖远端守护程序、网关或云服务；Easy Pair 也要求远端 `moshi-hook host setup`，不符合 Moke 零专属守护程序边界。ET 需要 `etserver`；Zellij、SSH-agent forwarding、外部 session deep link 仅在有需求证据时评估。Moshi 的 Tailscale 文档明确它没有内置 tailnet picker，Moke 不需要为了对齐增加 VPN 控制面。
7. 将最终路线图按 A/B/C 汇总，每个条目包含场景、复用点、依赖/限制、未来验收、相对投入，不写开发天数。A 固定包括：附件直达远端路径、进程死亡后的 tmux 恢复入口、显式项目/工作区入口、非秘密主机配置迁移、SFTP/SAF 设备验收；B 固定包括：tmux 窗口跳转、连接诊断、scrollback 搜索、host 搜索/Quick Connect、端口预览可发现性、按需 git diff；C 固定包括：语音草稿（暂缓至未来）、隐私锁、证据驱动手势、增量协议，以及明确排除的 agent/cloud/iOS 能力。语音仍是未来实现方向，但不进入近期开发排期，也不并入“明确不建议近期复制”。
8. 文档末尾列出可操作的未来验收场景，至少覆盖：同名文件覆盖确认后上传成功才插入路径；系统杀进程后重新选择 host 并附加同名 tmux、但不承诺旧滚屏；项目路径 basename 冲突不附错工作区；导出文件不含密码/PEM/passphrase，导入冲突不静默覆盖；tmux 窗口列表定位并切换，窗口消失时提示；本地搜索明确只查当前缓存；连接诊断不绕过 host-key 校验；点击 `http://127.0.0.1:5173/` 被认定为现有转发并打开能力。全部标记为未来验收，不冒充本次实测。

## Critical files & anchors

- `app/src/main/java/com/briqt/moke/terminal/Tmux.kt`: `DISCOVER_CMD`、`attachOrCreateCommand`、`paneCwdCmd`、`scrollSetupCmd`；判定会话层现状、窗口导航与项目路径建议边界。
- `app/src/main/java/com/briqt/moke/ui/TerminalScreen.kt`: `TextBlockComposer` 接线、滚屏/回底部、localhost 链接转发和终端顶栏；避免把既有输入、滚屏、预览写成缺失。
- `app/src/main/java/com/briqt/moke/data/HostStore.kt`: Keystore 密文持久化、不可读保护和内部 JSON；配置迁移建议必须与真实存储安全模型一致。
- `app/src/main/res/xml/backup_rules.xml`: Android 6–11 明确排除 `moke_hosts.preferences_pb`，证明系统备份不能承担主机迁移。
- `app/src/main/res/xml/data_extraction_rules.xml`: Android 12+ 云备份和设备直传同样排除主机数据库，支撑显式导出/导入建议。

## Verification

- 完稿后完整读取 `docs/moshi-moke-comparison.md`，确认标题、证据标签、三层模型、对照表、原路线图复核、新增机会、A/B/C 路线图、明确不建议复制、未来验收和核查声明均存在；文档内所有 Moke 路径/符号必须来自本计划列出的已读源码，不写未确认 API 或行为。
- 逐个读取文档引用的 Moshi 官方 URL，至少覆盖 `connections`、`terminal-sessions`、`multiplexer`、`jump-to`、`scrolling`、`voice`、`gestures`、`image-paste`、`browser-preview`、`hooks`、`chat-view`、`security-sync`、`notifications`、`tailscale`、`subscription`。`voice` 页仍须核查，并以“语音草稿暂缓至未来”的口径写入文档；页面未明确 Android 支持的能力仍写“官方文档声明”，不能从 meta 关键词推成 Android 实测。
- 对每个路线图条目检查五要素：用户场景、现有复用入口、依赖/限制、具体输入→期望输出的未来验收、低/中/高相对投入；缺任一项即补齐。重点复核项目目录与 tmux 会话名不是同一字段、客户端 scrollback 与 tmux 历史不是同一数据源、Keystore 加密与生物应用锁不是同一安全能力。
- 用 `grep` 检查文档至少出现 `官方文档声明`、`源码已见`、`待真机验证`、`不承诺恢复旧 emulator scrollback`、`上传成功后`、`不后台扫描`、`不绕过 TOFU`；检查 `PortForwards.start` 和 `ForwardPanel` 被写为已有实现而非缺失。
- 在仓库根目录运行 `git diff --check -- docs/moshi-moke-comparison.md`，期望无输出且退出码为 0；文档任务不运行 Gradle 构建或 JVM 测试。最后读取工作树差异，确认只新增/更新该 Markdown 文档，没有产品代码、配置或图片变化。

## Assumptions & contingencies

- 用户已确定保持 Android 纯客户端、不要求专属远端守护程序，允许用户明确触发的远端只读命令；文档不提出后台 agent 转录扫描、自动端口扫描、云推送或通用终端转录解析作为近期方案。
- 交付文件固定为 `docs/moshi-moke-comparison.md`。执行时若该文件仍不存在则新建；若用户在执行前已创建同名文件，先读取并保留其中与本任务不冲突的内容，再按本计划章节整合，不整文件盲目覆盖。
- 配置迁移建议固定为“版本化、可预览、默认排除秘密”；不提出直接复制 `HostStore` 密文，因为 Android Keystore 密钥不随备份迁移。是否未来支持用户主动加密秘密导出属于后续独立安全设计，不在本分析中替实现者选择格式或密码学方案。
- 项目入口建议固定优先使用用户明确保存/选择的远端路径；不读取 agent 私有转录来自动发现目录。若文档提到远端最近目录探测，只作为用户触发、只读、可失败退出的后续研究项。
- Moshi 专题正文只写 iOS 操作或未明确 Android 行为时，保持平台限定；Moke 所有未运行行为标记“待真机验证”。本次不做 Moshi 运行测评、Moke 设备验收、性能数字或开发工期估算。
