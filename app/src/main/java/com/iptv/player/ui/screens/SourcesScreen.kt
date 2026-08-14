package com.iptv.player.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.data.backup.ConfigBackup
import com.iptv.player.data.db.SourceEntity
import com.iptv.player.data.db.SourceType
import com.iptv.player.data.repo.SyncProgress
import com.iptv.player.data.repo.SyncResult
import com.iptv.player.ui.components.SettingRow
import com.iptv.player.ui.components.StateMessage
import com.iptv.player.ui.components.TvButton
import com.iptv.player.ui.components.TvTextField
import com.iptv.player.ui.theme.ErrorRed
import com.iptv.player.ui.util.relativeTime
import com.iptv.player.ui.util.tvFocusGroup
import com.iptv.player.ui.vm.SourcesViewModel

/**
 * Playlist management: the list on the left, everything you can do to the
 * selected one on the right.
 *
 * Destructive actions live in the right-hand panel rather than behind a
 * long-press or a context menu because a remote gives no affordance for
 * discovering hidden gestures, and deleting a playlist takes its favourites
 * and history with it.
 */
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel,
    activeSourceId: Long,
    onSetActive: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val sources by viewModel.sourceList.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<SourceEntity?>(null) }
    var renaming by remember { mutableStateOf<SourceEntity?>(null) }
    var signingIn by remember { mutableStateOf<SourceEntity?>(null) }

    // Keep the panel pointed at something real after a delete.
    LaunchedEffect(sources) {
        if (sources.none { it.id == selectedId }) selectedId = sources.firstOrNull()?.id
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ConfigBackup.MIME_TYPE)
    ) { uri ->
        // Credentials are deliberately never included — see SourcesViewModel
        // and ConfigBackup. Restoring an Xtream source from an export lands it
        // in this list with a "Sign in" row instead of its login; everything
        // else (server/EPG URLs, favourites) restores as-is.
        uri?.let { viewModel.export(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.import(it) } }

    confirmDelete?.let { source ->
        ConfirmPanel(
            message = stringResource(R.string.sources_delete_confirm, source.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.delete(source.id)
                confirmDelete = null
            },
            onCancel = { confirmDelete = null },
        )
        return
    }

    renaming?.let { source ->
        RenamePanel(
            initial = source.name,
            onSave = { name ->
                viewModel.rename(source.id, name)
                renaming = null
            },
            onCancel = { renaming = null },
        )
        return
    }

    signingIn?.let { source ->
        XtreamSignInPanel(
            sourceName = source.name,
            onSave = { username, password ->
                viewModel.attachXtreamCredentials(source.id, username, password)
                signingIn = null
            },
            onCancel = { signingIn = null },
        )
        return
    }

    if (sources.isEmpty()) {
        StateMessage(
            title = stringResource(R.string.sources_empty),
            body = stringResource(R.string.home_empty_body),
            actionLabel = stringResource(R.string.sources_add),
            onAction = onAdd,
        )
        return
    }

    Row(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.width(460.dp).fillMaxHeight()) {
            Text(stringResource(R.string.sources_title), style = MaterialTheme.typography.headlineSmall)

            LazyColumn(
                Modifier.weight(1f).padding(top = 16.dp).tvFocusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(sources, key = { it.id }) { source ->
                    val progress = ui.syncing[source.id]
                    SettingRow(
                        title = source.name,
                        summary = source.summaryLine(progress),
                        value = if (source.id == activeSourceId) stringResource(R.string.sources_active) else null,
                        onClick = { selectedId = source.id },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(text = stringResource(R.string.sources_add), onClick = onAdd)
                TvButton(
                    text = stringResource(R.string.sources_refresh_all),
                    primary = false,
                    onClick = { viewModel.syncAll() },
                )
            }
        }

        val selected = sources.firstOrNull { it.id == selectedId }
        if (selected != null) {
            SourceDetailPanel(
                source = selected,
                isActive = selected.id == activeSourceId,
                progress = ui.syncing[selected.id],
                lastResult = ui.lastResult,
                busyMessage = ui.busyMessage,
                onSetActive = { onSetActive(selected.id) },
                onRefresh = { viewModel.syncNow(selected.id) },
                onRename = { renaming = selected },
                onDelete = { confirmDelete = selected },
                onMoveUp = { viewModel.move(selected.id, -1) },
                onMoveDown = { viewModel.move(selected.id, +1) },
                // Launching a document picker that does not exist throws
                // ActivityNotFoundException and, worse, shows a system toast
                // the app cannot replace. Both paths are guarded: export falls
                // back to a fixed location, import explains itself.
                onExport = {
                    runCatching { exportLauncher.launch(ConfigBackup.SUGGESTED_FILENAME) }
                        .onFailure { viewModel.exportToDefaultLocation() }
                },
                onImport = {
                    runCatching { importLauncher.launch(arrayOf(ConfigBackup.MIME_TYPE, "text/plain", "*/*")) }
                        .onFailure { viewModel.reportNoFilePicker("importing a backup") }
                },
                onSignIn = { signingIn = selected },
                modifier = Modifier.weight(1f).padding(start = 28.dp),
            )
        }
    }
}

@Composable
private fun SourceDetailPanel(
    source: SourceEntity,
    isActive: Boolean,
    progress: SyncProgress?,
    lastResult: SyncResult?,
    busyMessage: String?,
    onSetActive: () -> Unit,
    onRefresh: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // An Xtream source with no stored login — the shape a backup import
    // produces, since exports never carry credentials. Distinct from a normal
    // sync failure: there is nothing to retry here, so it gets its own banner
    // and its own action instead of showing as a generic error.
    val needsSignIn = source.type == SourceType.XTREAM && source.credentialsCipher == null

    Column(modifier.fillMaxHeight()) {
        Text(source.name, style = MaterialTheme.typography.headlineSmall)
        Text(
            source.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(R.string.sources_counts, source.liveCount, source.movieCount, source.seriesCount),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            source.lastSyncAt?.let { stringResource(R.string.sources_last_sync, relativeTime(it)) }
                ?: stringResource(R.string.sources_never_synced),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (needsSignIn) {
            Text(
                stringResource(R.string.sources_needs_sign_in),
                style = MaterialTheme.typography.bodySmall,
                color = ErrorRed,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            source.lastSyncError?.let { error ->
                Text(
                    stringResource(R.string.sync_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        progress?.let {
            Text(
                it.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        (lastResult as? SyncResult.Success)?.stats?.let { stats ->
            if (stats.skipped > 0) {
                Text(
                    stringResource(R.string.sync_skipped_entries, stats.skipped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        busyMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }

        Column(
            Modifier.padding(top = 22.dp).fillMaxWidth().tvFocusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (needsSignIn) {
                SettingRow(
                    title = stringResource(R.string.sources_sign_in),
                    summary = stringResource(R.string.sources_sign_in_summary),
                    onClick = onSignIn,
                )
            }
            if (!isActive) {
                SettingRow(title = stringResource(R.string.sources_make_active), onClick = onSetActive)
            }
            SettingRow(title = stringResource(R.string.sources_refresh), onClick = onRefresh)
            SettingRow(title = stringResource(R.string.sources_rename), onClick = onRename)
            SettingRow(title = stringResource(R.string.sources_move_up), onClick = onMoveUp)
            SettingRow(title = stringResource(R.string.sources_move_down), onClick = onMoveDown)
            SettingRow(
                title = stringResource(R.string.sources_export),
                summary = "Server and EPG URLs and favourites are saved. Xtream logins are not included and must be re-entered after importing.",
                onClick = onExport,
            )
            SettingRow(title = stringResource(R.string.sources_import), onClick = onImport)
            SettingRow(title = stringResource(R.string.sources_delete), onClick = onDelete)
        }
    }
}

@Composable
private fun ConfirmPanel(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 80.dp, vertical = 64.dp)
    ) {
        Text(message, style = MaterialTheme.typography.titleLarge)
        Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Cancel takes focus, not the destructive action: the first press
            // after this appears should never be the one that deletes.
            TvButton(text = stringResource(R.string.action_cancel), primary = false, onClick = onCancel, autoFocus = true)
            TvButton(text = confirmLabel, onClick = onConfirm)
        }
    }
}

@Composable
private fun RenamePanel(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 80.dp, vertical = 64.dp)
    ) {
        TvTextField(
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.add_source_name),
            autoFocus = true,
            modifier = Modifier.width(560.dp),
        )
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = stringResource(R.string.action_save),
                enabled = text.isNotBlank(),
                onClick = { onSave(text) },
            )
            TvButton(text = stringResource(R.string.action_cancel), primary = false, onClick = onCancel)
        }
    }
}

/**
 * Attaches a login to an Xtream source that was imported without one.
 *
 * Just the two credential fields — server and EPG URL already came in with
 * the import, and re-asking for them would make the user retype something the
 * app already has.
 */
@Composable
private fun XtreamSignInPanel(
    sourceName: String,
    onSave: (username: String, password: String) -> Unit,
    onCancel: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 80.dp, vertical = 64.dp)
    ) {
        Text(stringResource(R.string.sources_sign_in), style = MaterialTheme.typography.headlineSmall)
        Text(
            sourceName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        TvTextField(
            value = username,
            onValueChange = { username = it },
            label = stringResource(R.string.add_source_username),
            autoFocus = true,
            modifier = Modifier.width(560.dp).padding(bottom = 16.dp),
        )
        TvTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.add_source_password),
            isPassword = true,
            modifier = Modifier.width(560.dp),
        )
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text = stringResource(R.string.action_save),
                enabled = username.isNotBlank() && password.isNotBlank(),
                onClick = { onSave(username, password) },
            )
            TvButton(text = stringResource(R.string.action_cancel), primary = false, onClick = onCancel)
        }
    }
}

@Composable
private fun SourceEntity.summaryLine(progress: SyncProgress?): String = when {
    progress != null -> progress.label()
    lastSyncError != null -> "⚠ $lastSyncError"
    lastSyncAt != null -> stringResource(R.string.sources_last_sync, relativeTime(lastSyncAt))
    else -> stringResource(R.string.sources_never_synced)
}

@Composable
private fun SyncProgress.label(): String = when (this) {
    SyncProgress.Connecting -> stringResource(R.string.add_source_testing)
    SyncProgress.Downloading -> stringResource(R.string.sync_downloading)
    is SyncProgress.Reading -> stringResource(R.string.sync_parsing, items)
    SyncProgress.Saving -> stringResource(R.string.sync_saving)
    SyncProgress.LoadingGuide -> stringResource(R.string.sync_epg)
}
