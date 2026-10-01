package com.briqt.moke.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrereleasePickTest {
    private fun releaseEntry(tag: String, pre: Boolean = false, draft: Boolean = false) =
        ReleaseEntry(tag, "https://github.com/ddwwbb/moke/releases/tag/$tag", pre, draft)
    private fun r(tag: String, pre: Boolean = false, draft: Boolean = false) = releaseEntry(tag, pre, draft)

    private val feed = listOf(
        r("v0.10.0-rc.4", pre = true),
        r("v0.10.0-rc.3", pre = true),
        r("v0.9.1"),
        r("v0.9"),
    )

    @Test
    fun `off keeps stable only`() {
        assertEquals("v0.9.1", UpdateChecker.pickLatest(feed, includePrerelease = false)?.tag)
        // A prerelease tag must not bypass the switch even if the API flag is wrong.
        assertEquals("v0.9.1", UpdateChecker.pickLatest(listOf(r("v0.11.0-rc.1")) + feed, false)?.tag)
    }

    @Test
    fun `on returns the newest preview with its exact release page`() {
        assertEquals(
            r("v0.10.0-rc.4", pre = true),
            UpdateChecker.pickLatest(feed, includePrerelease = true),
        )
    }

    @Test
    fun `drafts never count`() {
        val withDraft = listOf(r("v0.11.0", draft = true)) + feed
        assertEquals("v0.9.1", UpdateChecker.pickLatest(withDraft, false)?.tag)
        assertEquals("v0.10.0-rc.4", UpdateChecker.pickLatest(withDraft, true)?.tag)
    }

    @Test
    fun `github creation ordering is not version ordering`() {
        val outOfOrder = listOf(r("v0.9"), r("v0.9.2"), r("v0.9.1"))
        assertEquals("v0.9.2", UpdateChecker.pickLatest(outOfOrder, false)?.tag)
    }

    @Test
    fun `stable outranks its own prereleases`() {
        val released = listOf(r("v0.10.0"), r("v0.10.0-rc.4", pre = true))
        assertEquals("v0.10.0", UpdateChecker.pickLatest(released, true)?.tag)
    }

    @Test
    fun `invalid tags have no fallback candidate`() {
        assertNull(UpdateChecker.pickLatest(listOf(r("nightly"), r("latest")), false))
    }

    @Test
    fun `release pages must match the fork and the selected tag`() {
        val valid = r("v0.9.1")
        val newer = r("v0.10.0")
        val invalidUrls = listOf(
            "https://github.com/briqt/moke/releases/tag/v0.10.0",
            "https://github.com/ddwwbb/moke/releases",
            valid.url,
            "https://github.com.evil.test/ddwwbb/moke/releases/tag/v0.10.0",
            "https://github.com@evil.test/ddwwbb/moke/releases/tag/v0.10.0",
            "http://github.com/ddwwbb/moke/releases/tag/v0.10.0",
            "",
        )
        for (url in invalidUrls) {
            assertEquals(url, valid, UpdateChecker.pickLatest(listOf(newer.copy(url = url), valid), true))
        }
    }

    @Test
    fun `rc user is not told to downgrade to the current stable`() {
        assertFalse(UpdateChecker.isNewer("0.9.1", "0.10.0-rc.4"))
        assertTrue(UpdateChecker.isNewer("0.10.0-rc.5", "0.10.0-rc.4"))
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.10.0-rc.4"))
    }

    @Test
    fun `stable release beyond the first page is still picked`() {
        // 第一页全为预发布、稳定版在第 2 页：关闭预览开关时也必须选到它。
        val secondPageStable = listOf(
            *List(20) { i -> r("v0.10.0-rc.${i + 1}", pre = true) }.toTypedArray(),
            r("v0.11.0"),
        )
        assertEquals("v0.11.0", UpdateChecker.pickLatest(secondPageStable, includePrerelease = false)?.tag)
    }

}
