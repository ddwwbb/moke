package com.briqt.moke.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono
import com.briqt.moke.BuildConfig
import com.briqt.moke.update.UpdateChecker
import com.briqt.moke.update.UpdateInfo
import com.briqt.moke.update.UpdateStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    updateStatus: UpdateStatus,
    updateInfo: UpdateInfo?,
    includePrerelease: Boolean,
    onCheckUpdate: () -> Unit,
    onIncludePrerelease: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME
    val displayedUpdate = if (updateStatus == UpdateStatus.Idle) {
        updateInfo?.let { UpdateStatus.Available(it.tag, it.url) } ?: updateStatus
    } else updateStatus

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                expandedHeight = MokeDimens.topBarHeight,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            // 可滚：横屏（或加了行之后）内容会高于视口，否则最后一行被裁掉。
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 头部：名称 + 定位
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.app_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 固定版本行高度；长错误信息放在下一行，不挤掉版本号。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                InfoLabel(stringResource(R.string.label_version))
                Text("v$version", fontFamily = MokeMono, modifier = Modifier.weight(1f))
                when (val update = displayedUpdate) {
                    UpdateStatus.Checking -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp), strokeWidth = 2.dp,
                    )
                    is UpdateStatus.Available -> Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.url)))
                    }) { Text(stringResource(R.string.new_version, update.latest)) }
                    else -> TextButton(onClick = onCheckUpdate) {
                        Text(stringResource(R.string.check_update))
                    }
                }
            }
            when (val update = displayedUpdate) {
                is UpdateStatus.UpToDate -> Text(
                    stringResource(R.string.up_to_date),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is UpdateStatus.Failed -> Text(
                    update.message,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }

            SwitchRow(
                icon = Icons.Filled.Science,
                title = stringResource(R.string.include_prerelease),
                subtitle = stringResource(R.string.include_prerelease_sub),
                checked = includePrerelease,
                onCheckedChange = onIncludePrerelease,
            )


            NavRow(
                Icons.Filled.Code,
                stringResource(R.string.github_repo),
                REPO_LABEL,
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.REPO_URL)))
                    }
                },
            )
        }
    }
}

/** 仓库地址的展示形态（去掉协议前缀，副标题里更干净）。 */
private val REPO_LABEL = UpdateChecker.REPO_URL.removePrefix("https://")

@Composable
private fun InfoLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(72.dp),
    )
}
