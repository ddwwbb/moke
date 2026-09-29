package com.briqt.moke.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.briqt.moke.R
import com.briqt.moke.data.HostMigration
import com.briqt.moke.ui.theme.MokeDimens
import com.briqt.moke.ui.theme.MokeMono

/** UI only: the caller launches SAF, parses the document and persists an explicitly approved plan. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostMigrationScreen(
    preview: HostMigration.Preview?,
    error: String?,
    busy: Boolean,
    hasHosts: Boolean,
    onPickImport: () -> Unit,
    onExport: () -> Unit,
    onApply: (Map<Int, HostMigration.Decision>) -> Unit,
    onBack: () -> Unit,
) {
    // A newly parsed document is a new decision session, even when its entries happen to match.
    val decisions = remember(preview) { mutableStateMapOf<Int, HostMigration.Decision>() }
    val canApply = !busy && preview != null && preview.entries.isNotEmpty() &&
        preview.entries.all { decisions.containsKey(it.index) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.migration_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                expandedHeight = MokeDimens.topBarHeight,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (preview != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack, enabled = !busy) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = { onApply(decisions.toMap()) },
                        enabled = canApply,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.migration_confirm))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.migration_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.migration_credentials_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onPickImport, enabled = !busy) {
                            Text(stringResource(R.string.migration_import))
                        }
                        OutlinedButton(onClick = onExport, enabled = !busy && hasHosts) {
                            Text(stringResource(R.string.migration_export))
                        }
                    }
                    if (!hasHosts) {
                        Text(
                            stringResource(R.string.migration_no_hosts),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.migration_busy))
                    }
                }
            }
            if (!error.isNullOrBlank()) {
                item {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (preview != null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.migration_preview_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.migration_preview_count, preview.entries.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (preview.entries.isEmpty()) {
                    item { Text(stringResource(R.string.migration_empty_preview)) }
                }
                items(preview.entries, key = { it.index }) { entry ->
                    MigrationEntryCard(
                        entry = entry,
                        decision = decisions[entry.index],
                        busy = busy,
                        onDecision = { decisions[entry.index] = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun MigrationEntryCard(
    entry: HostMigration.Entry,
    decision: HostMigration.Decision?,
    busy: Boolean,
    onDecision: (HostMigration.Decision) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(entry.source.label.ifBlank { entry.source.host }, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.migration_source,
                    entry.source.username,
                    entry.source.host,
                    entry.source.port,
                ),
                fontFamily = MokeMono,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (entry.conflictId != null) {
                    stringResource(
                        R.string.migration_conflict,
                        entry.conflictLabel?.takeIf { it.isNotBlank() } ?: entry.conflictId,
                    )
                } else {
                    stringResource(R.string.migration_no_conflict)
                },
                color = if (entry.conflictId != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            val choices = if (entry.conflictId != null) CONFLICT_CHOICES else NEW_CHOICES
            choices.forEach { choice ->
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = !busy) { onDecision(choice) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = decision == choice,
                        onClick = null,
                        enabled = !busy,
                    )
                    Text(
                        stringResource(
                            when (choice) {
                                HostMigration.Decision.ADD -> R.string.migration_add
                                HostMigration.Decision.OVERWRITE -> R.string.migration_overwrite
                                HostMigration.Decision.SKIP -> R.string.migration_skip
                            },
                        ),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private val CONFLICT_CHOICES = listOf(
    HostMigration.Decision.ADD,
    HostMigration.Decision.OVERWRITE,
    HostMigration.Decision.SKIP,
)
private val NEW_CHOICES = listOf(HostMigration.Decision.ADD, HostMigration.Decision.SKIP)
