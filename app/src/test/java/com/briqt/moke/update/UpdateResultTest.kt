package com.briqt.moke.update

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateResultTest {
    private fun releaseEntry(tag: String, pre: Boolean = false, draft: Boolean = false) =
        ReleaseEntry(tag, "https://github.com/ddwwbb/moke/releases/tag/$tag", pre, draft)
    private fun r(tag: String, pre: Boolean = false, draft: Boolean = false) = releaseEntry(tag, pre, draft)

    private fun result(entries: List<ReleaseEntry>, current: String = "0.9", preview: Boolean = false) =
        UpdateChecker.evaluate(current, entries, preview, "no releases", "invalid release data")

    @Test
    fun `no eligible release is not evidence that current version is latest`() {
        assertEquals(UpdateStatus.Failed("no releases"), result(UpdateChecker.parseList("[]")))
        assertEquals(UpdateStatus.Failed("no releases"), result(listOf(r("v1.0.0", draft = true))))
        assertEquals(UpdateStatus.Failed("no releases"), result(listOf(r("v1.0.0-rc.1", pre = true))))
    }

    @Test
    fun `invalid candidate or current version is a failure not up to date`() {
        assertEquals(UpdateStatus.Failed("invalid release data"), result(listOf(r("nightly"))))
        assertEquals(
            UpdateStatus.Failed("invalid release data"),
            result(listOf(r("v1.0.0").copy(url = "https://github.com/briqt/moke/releases/tag/v1.0.0"))),
        )
        assertEquals(UpdateStatus.Failed("invalid release data"), result(listOf(r("v1.0.0")), current = "debug"))
    }

    @Test
    fun `fork JSON preview yields its exact page without changing displayed tag`() {
        val entries = UpdateChecker.parseList(
            """[{"tag_name":"v0.10.0-rc.1","html_url":"https://github.com/ddwwbb/moke/releases/tag/v0.10.0-rc.1","prerelease":true,"draft":false}]"""
        )
        assertEquals(
            UpdateStatus.Available("v0.10.0-rc.1", "https://github.com/ddwwbb/moke/releases/tag/v0.10.0-rc.1"),
            result(entries, preview = true),
        )
    }

    @Test
    fun `equal or older valid release never offers a downgrade`() {
        assertEquals(UpdateStatus.UpToDate("0.9"), result(listOf(r("v0.9.0"))))
        assertEquals(UpdateStatus.UpToDate("0.10.0-rc.4"), result(listOf(r("v0.9.1")), current = "0.10.0-rc.4"))
    }

    @Test(expected = JSONException::class)
    fun `malformed release data is not silently skipped`() {
        UpdateChecker.parseList("""[{"name":"v1.0.0","html_url":"https://github.com/ddwwbb/moke/releases/tag/v1.0.0","prerelease":false,"draft":false}]""")
    }

    @Test
    fun `stable release on a later page offers update not no releases`() {
        val firstPageAllPre = List(20) { i -> r("v0.10.0-rc.${i + 1}", pre = true) }
        val laterStable = firstPageAllPre + r("v0.11.0")
        assertEquals(
            UpdateStatus.Available(
                "v0.11.0",
                "https://github.com/ddwwbb/moke/releases/tag/v0.11.0",
            ),
            result(laterStable, preview = false),
        )
        // 只见第一页（未翻页）时也不能谎报已是最新——这是翻页必须存在的行为锚点。
        assertEquals(UpdateStatus.Failed("no releases"), result(firstPageAllPre, preview = false))
    }
}
