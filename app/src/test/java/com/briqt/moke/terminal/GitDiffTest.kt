package com.briqt.moke.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitDiffTest {

    @Test
    fun `identifies git not installed and not a git repo`() {
        assertEquals(GitDiffResult.GitNotInstalled, GitDiff.parse("__MOKE_GIT__:missing\n"))
        assertEquals(GitDiffResult.NotGitRepo, GitDiff.parse("__MOKE_GIT__:not_repo\n"))
        assertTrue(GitDiff.parse("") is GitDiffResult.Error)
    }

    @Test
    fun `parses empty changes as Empty result`() {
        val emptyOutput = """
            __MOKE_GIT__:ready
            ===__MOKE_GIT_DIFF_START__===
        """.trimIndent()
        assertEquals(GitDiffResult.Empty, GitDiff.parse(emptyOutput))
    }

    @Test
    fun `parses modified and added files with lines and hunk numbers`() {
        val raw = """
            __MOKE_GIT__:ready
             M src/App.kt
            ?? docs/说明.md
            ===__MOKE_GIT_DIFF_START__===
            diff --git a/src/App.kt b/src/App.kt
            index 1234..5678 100644
            --- a/src/App.kt
            +++ b/src/App.kt
            @@ -10,3 +10,4 @@
             fun main() {
            -    println("old")
            +    println("new")
            +    println("extra")
             }
        """.trimIndent()

        val result = GitDiff.parse(raw) as GitDiffResult.Success
        val summary = result.summary
        assertEquals(2, summary.files.size)
        assertEquals(2, summary.totalAdditions)
        assertEquals(1, summary.totalDeletions)
        assertFalse(summary.isTruncated)

        val appFile = summary.files.first { it.path == "src/App.kt" }
        assertEquals(GitFileChangeType.MODIFIED, appFile.changeType)
        assertEquals(2, appFile.additions)
        assertEquals(1, appFile.deletions)
        assertEquals(6, appFile.lines.size)
        assertEquals(GitDiffLineType.HEADER, appFile.lines[0].type)
        assertEquals(GitDiffLineType.CONTEXT, appFile.lines[1].type)
        assertEquals(GitDiffLineType.DELETE, appFile.lines[2].type)
        assertEquals("    println(\"old\")", appFile.lines[2].text)
        assertEquals(11, appFile.lines[2].oldLineNum)
        assertEquals(GitDiffLineType.ADD, appFile.lines[3].type)
        assertEquals("    println(\"new\")", appFile.lines[3].text)
        assertEquals(11, appFile.lines[3].newLineNum)

        val untrackedFile = summary.files.first { it.path == "docs/说明.md" }
        assertEquals(GitFileChangeType.UNTRACKED, untrackedFile.changeType)
        assertEquals(0, untrackedFile.additions)
    }

    @Test
    fun `passes quoted project path as separate shell argument`() {
        val cmd = GitDiff.diffCommand("/srv/my project/with'quote")
        assertTrue(cmd.contains("cd \"\$1\""))
        assertTrue(cmd.contains("' sh '/srv/my project/with'\\''quote'"))
        assertTrue(cmd.contains("git -c core.quotepath=false diff"))
        assertTrue(cmd.contains("head -c ${GitDiff.MAX_DIFF_BYTES}"))
        assertTrue(GitDiff.diffCommand("/srv/repo ").endsWith("' sh '/srv/repo '"))
    }

    // ---------- 目录解析链 ----------

    private fun resolve(
        osc7: String? = null,
        project: String = "",
        name: String? = null,
        named: String? = null,
        sessions: String? = null,
    ) = GitDiff.resolveTargetDir(osc7, project, name, named, sessions)

    @Test
    fun `osc7 cwd wins over configured project path`() {
        assertEquals(
            GitDiff.DirResolution.Dir("/work/actual"),
            resolve(osc7 = "/work/actual", project = "/work/configured"),
        )
    }

    @Test
    fun `project path falls back when no osc7`() {
        assertEquals(
            GitDiff.DirResolution.Dir("/srv/app/"),
            resolve(project = "/srv/app/", sessions = null),
        )
    }

    @Test
    fun `named tmux session cwd used when probe succeeds`() {
        assertEquals(
            GitDiff.DirResolution.Dir("/repo"),
            resolve(name = "agent", named = "/repo\n", sessions = null),
        )
    }

    @Test
    fun `named probe failure does not silently select another session`() {
        assertEquals(GitDiff.DirResolution.ProbeFailed, resolve(name = "agent", named = null, sessions = "other:/repo\n"))
        assertEquals(GitDiff.DirResolution.Missing, resolve(name = "agent", named = "", sessions = "other:/repo\n"))
    }

    @Test
    fun `single session heuristic resolves`() {
        assertEquals(
            GitDiff.DirResolution.Dir("/only/repo"),
            resolve(sessions = "main:/only/repo\n"),
        )
    }

    @Test
    fun `multiple sessions never guess`() {
        assertEquals(
            GitDiff.DirResolution.Missing,
            resolve(sessions = "a:/one\nb:/two\n"),
        )
    }

    @Test
    fun `sessions probe failure is distinct from missing directory`() {
        assertEquals(GitDiff.DirResolution.ProbeFailed, resolve(sessions = null))
        assertEquals(GitDiff.DirResolution.Missing, resolve(sessions = ""))
        assertEquals(GitDiff.DirResolution.ProbeFailed, resolve(sessions = "__MOKE_TMUX__:failed\n"))
    }

    @Test
    fun `invalid reported paths cannot become remote command arguments`() {
        assertEquals(GitDiff.DirResolution.Dir("/configured"), resolve(osc7 = "/tmp\nnext", project = "/configured"))
        assertEquals(GitDiff.DirResolution.Missing, resolve(name = "agent", named = "warning\n/repo", sessions = ""))
    }

    @Test
    fun `preserves trailing spaces in current directory`() {
        assertEquals(GitDiff.DirResolution.Dir("/srv/repo "), resolve(osc7 = "/srv/repo ", project = "/other"))
        assertEquals(GitDiff.DirResolution.Dir("/srv/repo "), resolve(name = "work", named = "/srv/repo \n"))
    }

    @Test
    fun `rejects named pane output with additional absolute line`() {
        assertEquals(GitDiff.DirResolution.Missing, resolve(name = "work", named = "/wrong\n/right\n"))
    }

}
