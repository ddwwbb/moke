package com.briqt.moke.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.briqt.moke.R
import com.briqt.moke.data.KeyboardMode
import com.briqt.moke.data.ScrollMode
import com.briqt.moke.ui.theme.MokeDimens

/**
 * 「终端与输入」设置页：终端行为 + 输入相关的开关都归到这里。
 * 一级设置只留分组入口，具体项集中在本页——后续加「会话持久化 / 滚屏行数 / 附加键布局」等也落这儿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsScreen(
    keyboardMode: KeyboardMode,
    scrollMode: ScrollMode,
    tmuxScrollSetup: Boolean,
    keepScreenOn: Boolean,
    confirmClose: Boolean,
    autoTrustNewHostKey: Boolean,
    terminalAlerts: Boolean,
    onTerminalAlerts: (Boolean) -> Unit,
    onKeyboardMode: (KeyboardMode) -> Unit,
    onScrollMode: (ScrollMode) -> Unit,
    onTmuxScrollSetup: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onConfirmClose: (Boolean) -> Unit,
    onAutoTrustNewHostKey: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var kbDialog by remember { mutableStateOf(false) }
    var scrollDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    val powerManager = remember(context) { requireNotNull(context.getSystemService(PowerManager::class.java)) }
    var batteryExempt by remember(context) {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    var batterySettingsFailed by remember { mutableStateOf(false) }
    DisposableEffect(view, context) {
        val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = powerManager.isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.menu_terminal_input),
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
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NavRow(
                Icons.Filled.KeyboardAlt,
                stringResource(R.string.keyboard_mode),
                stringResource(keyboardModeLabel(keyboardMode)),
                onClick = { kbDialog = true },
            )
            NavRow(
                Icons.Filled.SwipeVertical,
                stringResource(R.string.scroll_mode),
                stringResource(scrollModeLabel(scrollMode)),
                onClick = { scrollDialog = true },
            )
            SwitchRow(
                icon = Icons.Filled.SwipeVertical,
                title = stringResource(R.string.menu_tmux_scroll_setup),
                subtitle = stringResource(R.string.menu_tmux_scroll_setup_sub),
                checked = tmuxScrollSetup,
                onCheckedChange = onTmuxScrollSetup,
            )
            SwitchRow(
                icon = Icons.Filled.LightMode,
                title = stringResource(R.string.menu_keep_screen_on),
                subtitle = stringResource(R.string.menu_keep_screen_on_sub),
                checked = keepScreenOn,
                onCheckedChange = onKeepScreenOn,
            )
            NavRow(
                Icons.Filled.BatteryFull,
                stringResource(R.string.menu_background_battery),
                stringResource(
                    if (batteryExempt) R.string.background_battery_exempt
                    else R.string.background_battery_optimized,
                ),
                onClick = {
                    // 仅用户主动点击才请求豁免；已有豁免时打开系统列表，方便用户调整。
                    val intent = if (batteryExempt) {
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    } else {
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                    }
                    try {
                        context.startActivity(intent)
                        batterySettingsFailed = false
                    } catch (_: ActivityNotFoundException) {
                        batterySettingsFailed = true
                    } catch (_: SecurityException) {
                        batterySettingsFailed = true
                    }
                },
            )
            Text(
                stringResource(R.string.background_battery_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (batterySettingsFailed) {
                Text(
                    stringResource(R.string.background_battery_settings_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            SwitchRow(
                icon = Icons.Filled.Shield,
                title = stringResource(R.string.menu_confirm_close),
                subtitle = stringResource(R.string.menu_confirm_close_sub),
                checked = confirmClose,
                onCheckedChange = onConfirmClose,
            )
            SwitchRow(
                icon = Icons.Filled.NotificationsActive,
                title = stringResource(R.string.menu_terminal_alerts),
                subtitle = stringResource(R.string.menu_terminal_alerts_sub),
                checked = terminalAlerts,
                onCheckedChange = onTerminalAlerts,
            )
            SwitchRow(
                icon = Icons.Filled.Key,
                title = stringResource(R.string.menu_auto_trust_hostkey),
                subtitle = stringResource(R.string.menu_auto_trust_hostkey_sub),
                checked = autoTrustNewHostKey,
                onCheckedChange = onAutoTrustNewHostKey,
            )
        }
    }

    if (scrollDialog) {
        ScrollModeDialog(
            current = scrollMode,
            onPick = { onScrollMode(it); scrollDialog = false },
            onDismiss = { scrollDialog = false },
        )
    }

    if (kbDialog) {
        KeyboardModeDialog(
            current = keyboardMode,
            onPick = { onKeyboardMode(it); kbDialog = false },
            onDismiss = { kbDialog = false },
        )
    }
}
