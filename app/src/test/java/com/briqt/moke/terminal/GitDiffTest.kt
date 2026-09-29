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
    fun `generates safe shell command quoting project path`() {
        val cmd = GitDiff.diffCommand("/srv/my project/with'quote")
        assertTrue(cmd.contains("cd '/srv/my project/with'\\''quote'"))
        assertTrue(cmd.contains("git -c core.quotepath=false diff"))
        assertTrue(cmd.contains("head -c ${GitDiff.MAX_DIFF_BYTES}"))
    }
}
