package com.iptv.player.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import com.iptv.player.ui.components.Chip
import com.iptv.player.ui.components.ChipSpacing
import com.iptv.player.ui.components.TvButton
import com.iptv.player.ui.components.TvTextField
import com.iptv.player.ui.theme.ErrorRed
import com.iptv.player.ui.util.tvFocusGroup
import com.iptv.player.ui.vm.SourcesViewModel

private enum class SourceKind { M3U_URL, M3U_FILE, XTREAM }

/**
 * The add-playlist form.
 *
 * All three source types share one screen with the fields swapped, rather than
 * a wizard. Every extra screen is another set of D-pad presses, and the form is
 * short enough that showing the whole thing at once is less work than paging
 * through it — which matters because this is the one screen a user has to get
 * through before the app does anything at all.
 *
 * Text entry on a TV is genuinely painful, which is why the URL and server
 * fields tolerate sloppy input (see `XtreamClient.normaliseBase`) and why
 * "Test connection" exists: it is much cheaper to check a 40-character
 * password once here than to discover it was wrong after a failed import.
 */
@Composable
fun AddSourceScreen(
    viewModel: SourcesViewModel,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    /**
     * Escape hatch to Settings.
     *
     * This screen is where a first-run viewer lands, it has no side
     * navigation, and Back deliberately keeps them here while they have no
     * playlists — so without this there is genuinely no route out. That
     * matters beyond convenience: the internet-sharing consent dialog shown
     * moments earlier promises "You can turn it off at any time in Settings",
     * and a viewer who accepts and immediately reconsiders could not act on
     * that promise until they had added a playlist first.
     */
    onOpenSettings: () -> Unit,
) {
    var kind by remember { mutableStateOf(SourceKind.M3U_URL) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var userAgent by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileLabel by remember { mutableStateOf<String?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val ui by viewModel.ui.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileUri = uri
            fileLabel = uri.lastPathSegment
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        }
    }

    val errorName = stringResource(R.string.add_source_error_name)
    val errorUrl = stringResource(R.string.add_source_error_url)
    val errorCredentials = stringResource(R.string.add_source_error_credentials)
    val errorFile = stringResource(R.string.add_source_error_file)
    val noPickerMessage = stringResource(R.string.add_source_no_file_picker)

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 28.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.add_source_title), style = MaterialTheme.typography.headlineSmall)

        Row(
            Modifier.padding(top = 18.dp, bottom = 22.dp).tvFocusGroup(),
            horizontalArrangement = ChipSpacing,
        ) {
            Chip(
                label = stringResource(R.string.add_source_type_m3u_url),
                selected = kind == SourceKind.M3U_URL,
                onClick = { kind = SourceKind.M3U_URL; viewModel.clearTestResult() },
            )
            Chip(
                label = stringResource(R.string.add_source_type_m3u_file),
                selected = kind == SourceKind.M3U_FILE,
                onClick = { kind = SourceKind.M3U_FILE; viewModel.clearTestResult() },
            )
            Chip(
                label = stringResource(R.string.add_source_type_xtream),
                selected = kind == SourceKind.XTREAM,
                onClick = { kind = SourceKind.XTREAM; viewModel.clearTestResult() },
            )
        }

        val fieldWidth = Modifier.width(680.dp)

        TvTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.add_source_name),
            placeholder = stringResource(R.string.add_source_name_hint),
            modifier = fieldWidth.padding(bottom = 16.dp),
        )

        when (kind) {
            SourceKind.M3U_URL -> TvTextField(
                value = url,
                onValueChange = { url = it },
                label = stringResource(R.string.add_source_url),
                placeholder = stringResource(R.string.add_source_url_hint),
                modifier = fieldWidth.padding(bottom = 16.dp),
            )

            SourceKind.M3U_FILE -> Column(Modifier.padding(bottom = 16.dp)) {
                TvButton(
                    text = stringResource(R.string.add_source_pick_file),
                    primary = false,
                    // Not restricted to a single MIME type: TV file providers
                    // report .m3u as text/plain, application/octet-stream or
                    // nothing at all depending on the vendor, and a strict
                    // filter makes the file invisible in the picker.
                    //
                    // Guarded because many TV devices ship no document picker
                    // at all, in which case this throws and the platform shows
                    // an unhelpful toast over the app.
                    onClick = {
                        runCatching { filePicker.launch(arrayOf("*/*")) }
                            .onFailure { validationError = noPickerMessage }
                    },
                )
                fileLabel?.let {
                    Text(
                        stringResource(R.string.add_source_file_chosen, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            SourceKind.XTREAM -> {
                TvTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = stringResource(R.string.add_source_server),
                    placeholder = stringResource(R.string.add_source_server_hint),
                    modifier = fieldWidth.padding(bottom = 16.dp),
                )
                TvTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(R.string.add_source_username),
                    modifier = fieldWidth.padding(bottom = 16.dp),
                )
                TvTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.add_source_password),
                    isPassword = true,
                    modifier = fieldWidth.padding(bottom = 16.dp),
                )
            }
        }

        TvTextField(
            value = epgUrl,
            onValueChange = { epgUrl = it },
            label = stringResource(R.string.add_source_epg_url),
            placeholder = stringResource(R.string.add_source_epg_url_hint),
            modifier = fieldWidth.padding(bottom = 16.dp),
        )

        TvTextField(
            value = userAgent,
            onValueChange = { userAgent = it },
            label = stringResource(R.string.add_source_user_agent),
            modifier = fieldWidth.padding(bottom = 20.dp),
        )

        (validationError ?: ui.testMessage)?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (ui.testSucceeded && validationError == null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    ErrorRed
                },
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kind == SourceKind.XTREAM) {
                TvButton(
                    text = if (ui.testing) {
                        stringResource(R.string.add_source_testing)
                    } else {
                        stringResource(R.string.add_source_test)
                    },
                    primary = false,
                    enabled = !ui.testing && server.isNotBlank() && username.isNotBlank(),
                    onClick = {
                        validationError = null
                        viewModel.testXtream(server, username, password, userAgent.ifBlank { null })
                    },
                )
            }

            TvButton(
                text = stringResource(R.string.add_source_save),
                onClick = {
                    validationError = when {
                        name.isBlank() -> errorName
                        kind == SourceKind.M3U_URL && !url.isHttpUrl() -> errorUrl
                        kind == SourceKind.M3U_FILE && fileUri == null -> errorFile
                        kind == SourceKind.XTREAM &&
                            (server.isBlank() || username.isBlank() || password.isBlank()) -> errorCredentials
                        else -> null
                    }
                    if (validationError != null) return@TvButton

                    val epg = epgUrl.ifBlank { null }
                    val agent = userAgent.ifBlank { null }
                    when (kind) {
                        SourceKind.M3U_URL -> viewModel.addM3uUrl(name, url, epg, agent) { onDone() }
                        SourceKind.M3U_FILE -> viewModel.addM3uFile(name, fileUri!!, epg) { onDone() }
                        SourceKind.XTREAM ->
                            viewModel.addXtream(name, server, username, password, epg, agent) { onDone() }
                    }
                },
            )

            TvButton(text = stringResource(R.string.action_cancel), primary = false, onClick = onCancel)

            // Last in the row deliberately: it is the least-primary action
            // here, but it must exist — see onOpenSettings.
            TvButton(
                text = stringResource(R.string.nav_settings),
                primary = false,
                onClick = onOpenSettings,
            )
        }
    }
}

private fun String.isHttpUrl(): Boolean =
    startsWith("http://", true) || startsWith("https://", true)
