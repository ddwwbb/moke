package com.briqt.moke.terminal

/**
 * 远端 Git Diff 的只读抓取命令与数据模型。
 * 纯逻辑、无 Android 依赖，可在 JVM 上直接运行单元测试。
 */

enum class GitFileChangeType {
    MODIFIED,
    ADDED,
    DELETED,
    RENAMED,
    UNTRACKED,
}

enum class GitDiffLineType {
    CONTEXT,
    ADD,
    DELETE,
    HEADER,
}

data class GitDiffLine(
    val type: GitDiffLineType,
    val text: String,
    val oldLineNum: Int? = null,
    val newLineNum: Int? = null,
)

data class GitFileDiff(
    val path: String,
    val oldPath: String? = null,
    val changeType: GitFileChangeType,
    val additions: Int = 0,
    val deletions: Int = 0,
    val lines: List<GitDiffLine> = emptyList(),
)

data class GitDiffSummary(
    val files: List<GitFileDiff>,
    val totalAdditions: Int,
    val totalDeletions: Int,
    val isTruncated: Boolean = false,
)

sealed interface GitDiffResult {
    data class Success(val summary: GitDiffSummary) : GitDiffResult
    data object Empty : GitDiffResult
    data object NotGitRepo : GitDiffResult
    data object GitNotInstalled : GitDiffResult
    data object NoProjectPath : GitDiffResult
    data class Error(val message: String) : GitDiffResult
}

object GitDiff {
    private const val TAG_READY = "__MOKE_GIT__:ready"
    private const val TAG_NOT_REPO = "__MOKE_GIT__:not_repo"
    private const val TAG_MISSING = "__MOKE_GIT__:missing"
    private const val DELIM_DIFF = "===__MOKE_GIT_DIFF_START__==="

    /** 最多抓取的 diff 文本字节数（128KB），防止大变动撑爆内存与网络。 */
    const val MAX_DIFF_BYTES = 131072

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 生成在远端只读检查 Git 变动的 Shell 脚本。
     * - core.quotepath=false 确保中文路径原生显示而非八进制编码
     * - head -c 严格限制输出大小
     * - 零副作用、不执行任何写操作
     */
    fun diffCommand(projectPath: String): String = "sh -c '" +
        "if ! command -v git >/dev/null 2>&1; then " +
        "echo \"$TAG_MISSING\"; exit 0; " +
        "fi; " +
        "if [ ! -d \"\$1\" ]; then " +
        "echo \"$TAG_NOT_REPO\"; exit 0; " +
        "fi; " +
        "cd \"\$1\" || exit 0; " +
        "if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then " +
        "echo \"$TAG_NOT_REPO\"; exit 0; " +
        "fi; " +
        "echo \"$TAG_READY\"; " +
        "git -c core.quotepath=false status --porcelain=v1 -uall 2>/dev/null; " +
        "echo \"$DELIM_DIFF\"; " +
        "git -c core.quotepath=false diff --no-color -U2 HEAD 2>/dev/null | head -c $MAX_DIFF_BYTES; " +
        "echo \"\"' sh ${q(projectPath)}"

    /** 目录定位失败：区分确实无线索和管理通道执行失败。 */
    sealed interface DirResolution {
        data class Dir(val path: String) : DirResolution
        data object Missing : DirResolution
        data object ProbeFailed : DirResolution
    }

    /** OSC 7 路径不得改写；保存的主机路径已由编辑页验证和规范化。 */
    fun preferredDir(osc7Cwd: String?, projectPath: String): String? =
        absoluteDir(osc7Cwd) ?: absoluteDir(projectPath)

    /** 路径中的空格是有效文件名；只验证，绝不隐式改写 cwd。 */
    private fun absoluteDir(path: String?): String? =
        path?.takeIf { it.startsWith('/') && !it.contains('\n') && !it.contains('\r') && !it.contains('\u0000') }

    /** 保留显式来源优先级；失效的关联 tmux 不得退而猜测另一会话。 */
    fun resolveTargetDir(
        osc7Cwd: String?,
        projectPath: String,
        tmuxName: String?,
        namedProbeOut: String?,
        sessionsProbeOut: String?,
    ): DirResolution {
        preferredDir(osc7Cwd, projectPath)?.let { return DirResolution.Dir(it) }
        if (!tmuxName.isNullOrBlank()) {
            if (namedProbeOut == null) return DirResolution.ProbeFailed
            val panePath = namedProbeOut.removeSuffix("\r\n").removeSuffix("\n")
            return absoluteDir(panePath)?.let(DirResolution::Dir) ?: DirResolution.Missing
        }
        if (sessionsProbeOut == null || sessionsProbeOut.startsWith("__MOKE_TMUX__:failed")) return DirResolution.ProbeFailed
        return Tmux.parseSessionCwds(sessionsProbeOut)?.let(DirResolution::Dir) ?: DirResolution.Missing
    }

    /**
     * 解析执行命令的输出。
     */
    fun parse(rawOutput: String): GitDiffResult {
        val trimmed = rawOutput.trimStart()
        if (trimmed.startsWith(TAG_MISSING)) return GitDiffResult.GitNotInstalled
        if (trimmed.startsWith(TAG_NOT_REPO)) return GitDiffResult.NotGitRepo
        if (!trimmed.startsWith(TAG_READY)) {
            return if (trimmed.isBlank()) GitDiffResult.Error("No output from remote")
            else GitDiffResult.Error(trimmed.take(200))
        }

        val content = trimmed.removePrefix(TAG_READY).trimStart('\r', '\n')
        val delimIdx = content.indexOf(DELIM_DIFF)
        if (delimIdx < 0) {
            return GitDiffResult.Empty
        }

        val statusSection = content.substring(0, delimIdx).trim('\r', '\n')
        val diffSection = content.substring(delimIdx + DELIM_DIFF.length).trimStart('\r', '\n')

        val statusMap = parseStatus(statusSection)
        if (statusMap.isEmpty()) {
            return GitDiffResult.Empty
        }

        val fileDiffs = parseDiff(diffSection, statusMap)
        if (fileDiffs.isEmpty() && statusMap.isEmpty()) {
            return GitDiffResult.Empty
        }

        // 把只有 status 记录但 diff 没出现的（比如新增的未跟踪文件）也放进去
        val allFiles = mutableListOf<GitFileDiff>()
        allFiles.addAll(fileDiffs)
        val handledPaths = fileDiffs.map { it.path }.toSet()

        statusMap.forEach { (path, type) ->
            if (path !in handledPaths) {
                allFiles.add(
                    GitFileDiff(
                        path = path,
                        changeType = type,
                        additions = 0,
                        deletions = 0,
                        lines = emptyList(),
                    )
                )
            }
        }

        val totalAdd = allFiles.sumOf { it.additions }
        val totalDel = allFiles.sumOf { it.deletions }
        val isTruncated = diffSection.length >= MAX_DIFF_BYTES

        return GitDiffResult.Success(
            GitDiffSummary(
                files = allFiles.sortedBy { it.path },
                totalAdditions = totalAdd,
                totalDeletions = totalDel,
                isTruncated = isTruncated,
            )
        )
    }

    private fun parseStatus(statusOutput: String): Map<String, GitFileChangeType> {
        val result = mutableMapOf<String, GitFileChangeType>()
        statusOutput.lineSequence().filter { it.isNotBlank() }.forEach { rawLine ->
            val line = rawLine.trimEnd()
            // porcelain=v1: XY PATH (前2字符为状态码，其后为空格和路径)
            val match = Regex("""^([ MADRCU?!]{1,2})\s+(.+)$""").find(line)
            if (match != null) {
                val code = match.groupValues[1]
                val rawPath = match.groupValues[2].trim().removeSurrounding("\"")
                val type = when {
                    code.contains("?") -> GitFileChangeType.UNTRACKED
                    code.contains("A") -> GitFileChangeType.ADDED
                    code.contains("D") -> GitFileChangeType.DELETED
                    code.contains("R") -> GitFileChangeType.RENAMED
                    else -> GitFileChangeType.MODIFIED
                }
                val finalPath = if (rawPath.contains(" -> ")) rawPath.substringAfter(" -> ") else rawPath
                result[finalPath] = type
            }
        }
        return result
    }

    private fun parseDiff(diffOutput: String, statusMap: Map<String, GitFileChangeType>): List<GitFileDiff> {
        if (diffOutput.isBlank()) return emptyList()

        val result = mutableListOf<GitFileDiff>()
        val fileChunks = diffOutput.split(Regex("(?m)^diff --git "))

        for (chunk in fileChunks) {
            if (chunk.isBlank()) continue
            val lines = chunk.lines()
            val headerLine = lines.firstOrNull() ?: continue
            // a/path b/path
            val pathMatch = Regex("""^a/(.+?)\s+b/(.+)$""").find(headerLine)
            val path = pathMatch?.groupValues?.get(2) ?: headerLine.substringAfterLast(" b/").trim()
            val oldPath = pathMatch?.groupValues?.get(1)

            val parsedLines = mutableListOf<GitDiffLine>()
            var adds = 0
            var dels = 0
            var oldNum = 0
            var newNum = 0

            for (line in lines.drop(1)) {
                if (line.startsWith("index ") || line.startsWith("--- ") || line.startsWith("+++ ") || line.startsWith("new file ") || line.startsWith("deleted file ")) {
                    continue
                }
                if (line.startsWith("@@")) {
                    // @@ -1,5 +1,6 @@
                    parsedLines.add(GitDiffLine(GitDiffLineType.HEADER, line))
                    val hunkMatch = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(line)
                    if (hunkMatch != null) {
                        oldNum = hunkMatch.groupValues[1].toIntOrNull() ?: 1
                        newNum = hunkMatch.groupValues[2].toIntOrNull() ?: 1
                    }
                    continue
                }
                when {
                    line.startsWith("+") -> {
                        adds++
                        parsedLines.add(GitDiffLine(GitDiffLineType.ADD, line.substring(1), null, newNum++))
                    }
                    line.startsWith("-") -> {
                        dels++
                        parsedLines.add(GitDiffLine(GitDiffLineType.DELETE, line.substring(1), oldNum++, null))
                    }
                    line.startsWith(" ") -> {
                        parsedLines.add(GitDiffLine(GitDiffLineType.CONTEXT, line.substring(1), oldNum++, newNum++))
                    }
                }
            }

            val changeType = statusMap[path] ?: when {
                chunk.contains("new file mode") -> GitFileChangeType.ADDED
                chunk.contains("deleted file mode") -> GitFileChangeType.DELETED
                else -> GitFileChangeType.MODIFIED
            }

            result.add(
                GitFileDiff(
                    path = path,
                    oldPath = oldPath.takeIf { it != path },
                    changeType = changeType,
                    additions = adds,
                    deletions = dels,
                    lines = parsedLines,
                )
            )
        }
        return result
    }
}
