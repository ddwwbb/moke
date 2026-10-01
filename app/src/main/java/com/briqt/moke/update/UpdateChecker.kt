package com.briqt.moke.update

import android.content.Context
import com.briqt.moke.R
import com.briqt.moke.localized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/** 检查更新的结果状态。 */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class UpToDate(val current: String) : UpdateStatus
    data class Available(val latest: String, val url: String) : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
}

/** GitHub Releases 里的一条发布（只取判定所需字段）。 */
data class ReleaseEntry(
    val tag: String,
    val url: String,
    val prerelease: Boolean,
    val draft: Boolean,
)

/** 静默检查发现的新版：tag + 该发布自己的页面地址。 */
data class UpdateInfo(val tag: String, val url: String)

/** 仅从此 fork 的 GitHub Releases 查最新版并与当前版本比对。 */
object UpdateChecker {
    const val REPO_URL = "https://github.com/ddwwbb/moke"
    private const val PAGE_SIZE = 20
    private const val MAX_PAGES = 5
    private const val LIST_API = "https://api.github.com/repos/ddwwbb/moke/releases?per_page=$PAGE_SIZE&page="

    suspend fun check(
        current: String,
        context: Context,
        includePrerelease: Boolean = false,
    ): UpdateStatus = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val entries = mutableListOf<ReleaseEntry>()
            // 逐页拉取直到短页/空页：稳定版可能排在多个预发布之后的页里，
            // 只看第一页会在关闭预览开关时误报「暂无发行版」。上限防异常仓库无限翻页。
            for (page in 1..MAX_PAGES) {
                conn = (URL(LIST_API + page).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "moke")
                    // 阻塞 IO 由连接/读取超时约束；协程超时不能中断它，也不是总请求时限。
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    // 仓库被迁移时也不能悄悄跟随到另一个仓库。
                    instanceFollowRedirects = false
                }
                val code = conn.responseCode
                currentCoroutineContext().ensureActive()
                if (code == 404) return@withContext UpdateStatus.Failed(context.localized(R.string.update_none))
                if (code !in 200..299) return@withContext UpdateStatus.Failed("HTTP $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                currentCoroutineContext().ensureActive()
                val pageEntries = parseList(body)
                entries += pageEntries
                if (pageEntries.size < PAGE_SIZE) break
            }
            evaluate(
                current,
                entries,
                includePrerelease,
                context.localized(R.string.update_none),
                context.localized(R.string.update_parse_failed),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            UpdateStatus.Failed(context.localized(R.string.update_timeout))
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            UpdateStatus.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: JSONException) {
            currentCoroutineContext().ensureActive()
            UpdateStatus.Failed(context.localized(R.string.update_parse_failed))
        } finally {
            conn?.disconnect()
            currentCoroutineContext().ensureActive()
        }
    }

    private fun parseOne(o: JSONObject) = ReleaseEntry(
        tag = o.getString("tag_name"),
        url = o.getString("html_url"),
        prerelease = o.getBoolean("prerelease"),
        draft = o.getBoolean("draft"),
    )

    internal fun parseList(body: String): List<ReleaseEntry> {
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i -> parseOne(arr.getJSONObject(i)) }
    }

    /** 与网络路径共用的结果判定；没有候选不等于当前版本已是最新版。 */
    internal fun evaluate(
        current: String,
        entries: List<ReleaseEntry>,
        includePrerelease: Boolean,
        noReleaseMessage: String,
        parseFailureMessage: String,
    ): UpdateStatus {
        if (SemVer.parse(current) == null) return UpdateStatus.Failed(parseFailureMessage)
        if (entries.none { isEligible(it, includePrerelease) }) return UpdateStatus.Failed(noReleaseMessage)
        val picked = pickLatest(entries, includePrerelease)
            ?: return UpdateStatus.Failed(parseFailureMessage)
        return if (isNewer(picked.tag, current)) UpdateStatus.Available(picked.tag, picked.url)
        else UpdateStatus.UpToDate(current)
    }

    /** 不信任 API 的创建时间排序：排除草稿与不允许的预发布，按 SemVer 取最大者。 */
    fun pickLatest(entries: List<ReleaseEntry>, includePrerelease: Boolean): ReleaseEntry? = entries
        .asSequence()
        .filter { isEligible(it, includePrerelease) }
        .mapNotNull { entry ->
            SemVer.parse(entry.tag)?.takeIf { hasReleaseUrl(entry) }?.let { entry to it }
        }
        .maxByOrNull { it.second }
        ?.first

    private fun isEligible(entry: ReleaseEntry, includePrerelease: Boolean): Boolean =
        !entry.draft && (includePrerelease || (!entry.prerelease && SemVer.parse(entry.tag)?.prerelease == null))

    private fun hasReleaseUrl(entry: ReleaseEntry): Boolean {
        val uri = try {
            URI(entry.url)
        } catch (e: URISyntaxException) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            uri.path == "/ddwwbb/moke/releases/tag/${entry.tag}" &&
            uri.query == null && uri.fragment == null
    }

    /** SemVer 优先级比较；历史两段版本按 patch = 0 处理。 */
    fun isNewer(a: String, b: String): Boolean {
        val left = SemVer.parse(a) ?: return false
        val right = SemVer.parse(b) ?: return false
        return left.compareTo(right) > 0
    }

    private data class SemVer(
        val major: BigInteger,
        val minor: BigInteger,
        val patch: BigInteger,
        val prerelease: List<String>?,
    ) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
            minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
            patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }

            if (prerelease == null && other.prerelease == null) return 0
            if (prerelease == null) return 1
            if (other.prerelease == null) return -1

            for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
                val left = prerelease[i]
                val right = other.prerelease[i]
                val leftNumber = left.toBigIntegerOrNull()
                val rightNumber = right.toBigIntegerOrNull()
                val result = when {
                    leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                    leftNumber != null -> -1
                    rightNumber != null -> 1
                    else -> left.compareTo(right)
                }
                if (result != 0) return result
            }
            return prerelease.size.compareTo(other.prerelease.size)
        }

        companion object {
            private val pattern = Regex(
                """^[vV]?(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"""
            )

            fun parse(value: String): SemVer? {
                val match = pattern.matchEntire(value) ?: return null
                val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.')
                if (prerelease?.any { id -> id.all { it in '0'..'9' } && id.length > 1 && id[0] == '0' } == true) {
                    return null
                }
                return SemVer(
                    major = match.groupValues[1].toBigInteger(),
                    minor = match.groupValues[2].toBigInteger(),
                    patch = match.groupValues[3].takeIf { it.isNotEmpty() }?.toBigInteger() ?: BigInteger.ZERO,
                    prerelease = prerelease,
                )
            }
        }
    }
}
