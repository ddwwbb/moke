package com.briqt.moke.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.briqt.moke.R
import com.briqt.moke.terminal.GitDiffLine
import com.briqt.moke.terminal.GitDiffLineType
import com.briqt.moke.terminal.GitDiffResult
import com.briqt.moke.terminal.GitDiffSummary
import com.briqt.moke.terminal.GitFileChangeType
import com.briqt.moke.terminal.GitFileDiff
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.ui.theme.MokeShapes

private val ColorAddBg = Color(0x244CAF50)
private val ColorAddText = Color(0xFF2E7D32)
private val ColorDelBg = Color(0x24F44336)
private val ColorDelText = Color(0xFFC62828)
private val ColorHunkBg = Color(0x182196F3)
private val ColorHunkText = Color(0xFF1976D2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitDiffSheet(
    result: GitDiffResult?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onConfigureProject: () -> Unit,
) {
    if (result == null && !loading) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // 顶栏：标题 + 统计 / 截断提示 + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.git_diff_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (result is GitDiffResult.Success) {
                        val s = result.summary
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.git_diff_stat, s.files.size, s.totalAdditions, s.totalDeletions),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (s.isTruncated) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MokeShapes.pill,
                                ) {
                                    Text(
                                        stringResource(R.string.git_diff_truncated),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }

            HorizontalDivider()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                stringResource(R.string.git_diff_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    result is GitDiffResult.Empty -> {
                        EmptyStateView(
                            icon = Icons.Filled.CheckCircle,
                            tint = Color(0xFF2E7D32),
                            text = stringResource(R.string.git_diff_empty),
                        )
                    }

                    result is GitDiffResult.NotGitRepo -> {
                        EmptyStateView(
                            icon = Icons.Filled.Warning,
                            tint = MaterialTheme.colorScheme.error,
                            text = stringResource(R.string.git_diff_not_repo),
                        )
                    }

                    result is GitDiffResult.NoProjectPath -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            EmptyStateView(
                                icon = Icons.Filled.Folder,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = stringResource(R.string.git_diff_no_project_path),
                            )
                            Button(onClick = onConfigureProject) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("  " + stringResource(R.string.git_diff_configure_project))
                            }
                        }
                    }

                    result is GitDiffResult.GitNotInstalled -> {
                        EmptyStateView(
                            icon = Icons.Filled.Warning,
                            tint = MaterialTheme.colorScheme.error,
                            text = stringResource(R.string.git_diff_not_installed),
                        )
                    }

                    result is GitDiffResult.Error -> {
                        EmptyStateView(
                            icon = Icons.Filled.Warning,
                            tint = MaterialTheme.colorScheme.error,
                            text = result.message,
                        )
                    }

                    result is GitDiffResult.Success -> {
                        GitDiffFileList(summary = result.summary)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyStateView(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    text: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(44.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun GitDiffFileList(summary: GitDiffSummary) {
    // 默认展开所有修改的文件
    val expandedMap = remember(summary) {
        mutableStateMapOf<String, Boolean>().apply {
            summary.files.forEach { put(it.path, true) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(summary.files, key = { it.path }) { file ->
            val isExpanded = expandedMap[file.path] ?: true
            FileDiffCard(
                file = file,
                expanded = isExpanded,
                onToggle = { expandedMap[file.path] = !isExpanded },
            )
        }
    }
}

@Composable
private fun FileDiffCard(
    file: GitFileDiff,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 卡片头部（点击切换展开）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusBadge(file.changeType)

                Text(
                    file.path,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = MokeMono,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (file.additions > 0 || file.deletions > 0) {
                    Text(
                        "+${file.additions}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MokeMono,
                        color = ColorAddText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "-${file.deletions}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MokeMono,
                        color = ColorDelText,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider()
                    if (file.lines.isEmpty()) {
                        Text(
                            when (file.changeType) {
                                GitFileChangeType.UNTRACKED -> stringResource(R.string.git_status_untracked)
                                GitFileChangeType.DELETED -> stringResource(R.string.git_status_deleted)
                                else -> "—"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                    } else {
                        // 横向滚动容器包装代码行，保护代码缩进不被破坏
                        val hScroll = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(hScroll)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(vertical = 4.dp)
                        ) {
                            file.lines.forEach { line ->
                                DiffLineRow(line)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: GitDiffLine) {
    val (bg, textColor) = when (line.type) {
        GitDiffLineType.ADD -> ColorAddBg to ColorAddText
        GitDiffLineType.DELETE -> ColorDelBg to ColorDelText
        GitDiffLineType.HEADER -> ColorHunkBg to ColorHunkText
        GitDiffLineType.CONTEXT -> Color.Transparent to MaterialTheme.colorScheme.onSurface
    }

    val prefix = when (line.type) {
        GitDiffLineType.ADD -> "+"
        GitDiffLineType.DELETE -> "-"
        GitDiffLineType.HEADER -> " "
        GitDiffLineType.CONTEXT -> " "
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 行号列
        val numStr = when {
            line.type == GitDiffLineType.HEADER -> "@@"
            line.newLineNum != null -> line.newLineNum.toString()
            line.oldLineNum != null -> line.oldLineNum.toString()
            else -> ""
        }
        Text(
            numStr,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = MokeMono,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.width(36.dp),
        )

        Text(
            "$prefix ${line.text}",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            fontFamily = MokeMono,
            color = textColor,
            fontWeight = if (line.type == GitDiffLineType.HEADER) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun StatusBadge(type: GitFileChangeType) {
    val (labelRes, color) = when (type) {
        GitFileChangeType.MODIFIED -> R.string.git_status_modified to Color(0xFF1976D2)
        GitFileChangeType.ADDED -> R.string.git_status_added to Color(0xFF2E7D32)
        GitFileChangeType.DELETED -> R.string.git_status_deleted to Color(0xFFC62828)
        GitFileChangeType.UNTRACKED -> R.string.git_status_untracked to Color(0xFFEF6C00)
        GitFileChangeType.RENAMED -> R.string.git_status_renamed to Color(0xFF7B1FA2)
    }

    Surface(
        color = color.copy(alpha = 0.16f),
        shape = MokeShapes.pill,
    ) {
        Text(
            stringResource(labelRes),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
