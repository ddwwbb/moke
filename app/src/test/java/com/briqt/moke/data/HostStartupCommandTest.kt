package com.briqt.moke.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 协议级启动命令的生效口径（与 tmux 会话持久化互斥）。 */
class HostStartupCommandTest {

    @Test
    fun `空启动命令表示默认登录 shell`() {
        assertEquals("", Host().effectiveStartupCommand)
    }

    @Test
    fun `普通主机用配置的启动命令并去掉首尾空白`() {
        val h = Host(startupCommand = "  powershell.exe  ")
        assertEquals("powershell.exe", h.effectiveStartupCommand)
    }

    @Test
    fun `会话持久化为 tmux 时启动命令让位`() {
        val h = Host(startupCommand = "powershell.exe", persistence = SessionPersistence.TMUX)
        assertEquals("", h.effectiveStartupCommand)
    }

    @Test
    fun `只有空白的启动命令等同于未设置`() {
        assertEquals("", Host(startupCommand = "   ").effectiveStartupCommand)
    }

    @Test
    fun `启动命令能在 JSON 往返中保留`() {
        val h = Host(host = "example.com", username = "u", startupCommand = "powershell.exe")
        assertEquals("powershell.exe", Host.fromJson(h.toJson()).startupCommand)
    }

    @Test
    fun `旧数据没有启动命令字段时读成空`() {
        val json = Host(host = "example.com", username = "u").toJson().apply { remove("startupCommand") }
        assertEquals("", Host.fromJson(json).startupCommand)
    }

    @Test
    fun `explicit project path survives JSON roundtrip without changing legacy host fields`() {
        val host = Host(
            projectPath = "/srv/team work", group = "development",
            startupCommand = "zsh -l", loginCommand = "echo hello", tmuxSessionName = "daily",
        )
        val restored = Host.fromJson(host.toJson())
        assertEquals("/srv/team work", restored.projectPath)
        assertEquals("development", restored.group)
        assertEquals("zsh -l", restored.startupCommand)
        assertEquals("echo hello", restored.loginCommand)
        assertEquals("daily", restored.tmuxSessionName)
    }

    @Test
    fun `older host records have no implicit project directory`() {
        val oldRecord = Host(projectPath = "/srv/old", group = "ops").toJson().apply {
            remove("projectPath")
        }
        val restored = Host.fromJson(oldRecord)
        assertEquals("", restored.projectPath)
        assertEquals("ops", restored.group)
    }
}
