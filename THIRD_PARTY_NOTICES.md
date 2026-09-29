# 第三方组件与许可

moke 依赖或包含以下第三方组件。感谢这些项目的作者与维护者。

## Vendored（源码内置）

### terminal-emulator / terminal-view
- 来源：[termux/termux-app](https://github.com/termux/termux-app) 的 `terminal-emulator`、`terminal-view` 模块
- 上游来源：[Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)（Jack Palevich 等）
- 许可：**Apache License 2.0**
- 修改说明（均为加法式、向后兼容；逐项见各模块的 `README.md`）：
  - `terminal-view`：`TerminalRenderer` 增加可选行距倍数 / 字间距（em）参数，`TerminalView` 增加 `setFontSpacing(...)`；
    `TerminalView` 增加全屏程序内的滑动处理与滚屏回调，新增 `MokeScroll.java`（滑动决策）；文本选择工具条去掉无作用的 "More…" 项。
  - `terminal-emulator`：将 `TerminalSession.java` 改写为传输无关（面向新增的 `TerminalTransport.java`，不再 fork 本地 shell）；
    保留 `JNI.java` 与 `src/main/jni/termux.c`（PTY 子进程，`JNI` 改为 public），用于以独立子进程运行 `mosh-client`；
    `TerminalEmulator.java` 识别 DECSET 1003 并新增 `isBracketedPasteMode()`。其余文件未修改。

### 字体（打包进 APK）
- [Maple Mono NF CN](https://github.com/subframe7536/maple-font)（`app/src/main/res/font/maple_mono.ttf`）——**OFL**，Standard 与 Maple 变体共用的唯一内置字体。用户提供的 `MapleMono-NF-CN-unhinted.zip` 中 `MapleMono-NF-CN-Regular.ttf`，SHA-256：`8b4f149beaead3eac78ffb84adf31513ebf06fe6dbc6e181ccd9debad3c70f1a`。字体同时用于终端和界面等宽文本，包含中文及 Nerd 字形；不再提供字体下载或导入。

## 运行期依赖（构建时拉取，未内置源码）

| 组件 | 许可 | 用途 |
|---|---|---|
| [sshj](https://github.com/hierynomus/sshj) | Apache-2.0 | SSH 传输 |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`, `bcpkix-jdk18on`) | MIT-style (Bouncy Castle License) | 加密算法（sshj 依赖） |
| [EdDSA-Java](https://github.com/str4d/ed25519-java) (`net.i2p.crypto:eddsa`) | CC0-1.0 | ed25519 密钥（sshj 依赖） |
| AndroidX / Jetpack Compose / Material3 | Apache-2.0 | UI 框架 |
| Kotlin 标准库与协程 | Apache-2.0 | 语言运行时 |

## mosh native

mosh 集成：`libmosh-client.so`（mosh 1.4.0 前端 + [rjyo/mosh-android](https://github.com/rjyo/mosh-android) 预编译静态库）
作为**独立可执行二进制**（GPLv3），以独立子进程 + PTY/管道 IPC 运行，**不与产品层链接**。
二进制不入库，由 `scripts/build-mosh-native.sh` 从公开源码复现。分发含该二进制的 APK 须随附对应 GPLv3 源码；商业分发前请法务确认。
