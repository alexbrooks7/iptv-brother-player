package com.iptv.player.ui.vm

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.player.analytics.IptvAnalytics
import com.iptv.player.data.backup.ConfigBackup
import com.iptv.player.data.db.SourceEntity
import com.iptv.player.data.prefs.Credentials
import com.iptv.player.data.remote.HttpFailure
import com.iptv.player.data.remote.XtreamClient
import com.iptv.player.data.repo.CatalogImporter
import com.iptv.player.data.repo.SourceRepository
import com.iptv.player.data.repo.SyncProgress
import com.iptv.player.data.repo.SyncResult
import com.iptv.player.data.repo.reportSourceSynced
import com.iptv.player.util.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Playlists screen and the Add-playlist form are doing right now. */
data class SourcesUiState(
    /** Playlist id currently syncing, with its progress. */
    val syncing: Map<Long, SyncProgress> = emptyMap(),
    val lastResult: SyncResult? = null,
    val testing: Boolean = false,
    val testMessage: String? = null,
    val testSucceeded: Boolean = false,
    val busyMessage: String? = null,
)

class SourcesViewModel(
    private val sources: SourceRepository,
    private val importer: CatalogImporter,
    private val backup: ConfigBackup,
) : ViewModel() {

    private val _ui = MutableStateFlow(SourcesUiState())
    val ui: StateFlow<SourcesUiState> = _ui.asStateFlow()

    val sourceList: StateFlow<List<SourceEntity>> = sources.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Adding ---------------------------------------------------------

    fun addM3uUrl(name: String, url: String, epgUrl: String?, userAgent: String?, onDone: (Long) -> Unit) =
        viewModelScope.launch {
            val id = sources.addM3u(name, url, epgUrl, userAgent, fromFile = false)
            IptvAnalytics.event("source_added", mapOf("type" to "m3u_url"))
            onDone(id)
            syncNow(id, trigger = "add")
        }

    fun addM3uFile(name: String, uri: Uri, epgUrl: String?, onDone: (Long) -> Unit) =
        viewModelScope.launch {
            val id = sources.addM3u(name, uri.toString(), epgUrl, userAgent = null, fromFile = true)
            IptvAnalytics.event("source_added", mapOf("type" to "m3u_file"))
            onDone(id)
            syncNow(id, trigger = "add")
        }

    fun addXtream(
        name: String,
        server: String,
        username: String,
        password: String,
        epgUrl: String?,
        userAgent: String?,
        onDone: (Long) -> Unit,
    ) = viewModelScope.launch {
        val id = sources.addXtream(name, server, username, password, epgUrl, userAgent)
        IptvAnalytics.event("source_added", mapOf("type" to "xtream"))
        onDone(id)
        syncNow(id, trigger = "add")
    }

    /**
     * Checks credentials before the playlist is saved.
     *
     * Worth its own step because the alternative — save, sync, fail, explain —
     * leaves a broken playlist in the list that the user then has to delete
     * with a remote. It also surfaces the account's expiry and connection
     * limit, which is the information people actually want at this moment.
     */
    fun testXtream(server: String, username: String, password: String, userAgent: String?) =
        viewModelScope.launch {
            _ui.value = _ui.value.copy(testing = true, testMessage = null, testSucceeded = false)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    XtreamClient(server, Credentials(username, password), userAgent).authenticate()
                }
            }
            val account = result.getOrNull()
            _ui.value = when {
                result.isFailure -> _ui.value.copy(
                    testing = false,
                    testSucceeded = false,
                    testMessage = describeFailure(result.exceptionOrNull()),
                )

                account == null -> _ui.value.copy(
                    testing = false,
                    testSucceeded = false,
                    testMessage = "The server rejected this username and password.",
                )

                else -> _ui.value.copy(
                    testing = false,
                    testSucceeded = true,
                    testMessage = buildString {
                        append(account.status ?: "Active")
                        account.maxConnections?.let { append(" · $it connection(s)") }
                        account.expiresAt?.let {
                            append(" · expires ")
                            append(java.text.DateFormat.getDateInstance().format(java.util.Date(it)))
                        }
                    },
                )
            }
        }

    fun clearTestResult() {
        _ui.value = _ui.value.copy(testMessage = null, testSucceeded = false, testing = false)
    }

    // ---- Managing -------------------------------------------------------

    /**
     * [trigger] is "add" for a playlist's first sync, "manual" for a
     * user-pressed refresh — the two things this app can tell apart. The
     * background WorkManager path fires its own `source_synced` with
     * `trigger = "scheduled"` straight from RefreshWorker, since it never
     * goes through this ViewModel.
     */
    fun syncNow(sourceId: Long, trigger: String = "manual") = viewModelScope.launch {
        val source = sources.get(sourceId) ?: return@launch
        val result = importer.sync(source) { progress ->
            _ui.value = _ui.value.copy(syncing = _ui.value.syncing + (sourceId to progress))
        }
        _ui.value = _ui.value.copy(
            syncing = _ui.value.syncing - sourceId,
            lastResult = result,
        )
        reportSourceSynced(sourceType = source.type, trigger = trigger, result = result)
    }

    fun syncAll() = viewModelScope.launch {
        sources.all().filter { it.enabled }.forEach { source -> syncNow(source.id).join() }
    }

    fun rename(id: Long, name: String) = viewModelScope.launch { sources.rename(id, name) }

    fun delete(id: Long) = viewModelScope.launch { sources.delete(id) }

    fun move(id: Long, delta: Int) = viewModelScope.launch {
        val ordered = sources.all().map { it.id }.toMutableList()
        val from = ordered.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ordered.indices) return@launch
        ordered.removeAt(from)
        ordered.add(to, id)
        sources.reorder(ordered)
    }

    // ---- Backup ---------------------------------------------------------

    /**
     * Exports never include provider credentials — the encrypted copy on disk
     * is bound to this device's Keystore and useless elsewhere, and writing
     * the password in plain text into a file that can end up on a shared USB
     * stick is not something the app does. See [ConfigBackup]. An imported
     * Xtream source lands in Playlists with a "sign in" prompt instead of its
     * login — [attachXtreamCredentials] is how that gets resolved.
     */
    fun export(target: Uri) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busyMessage = "Exporting…")
        val result = backup.export(target)
        _ui.value = _ui.value.copy(
            busyMessage = result.fold(
                onSuccess = { "Exported $it playlist(s). Xtream logins are not included — re-enter them after importing." },
                onFailure = { "Export failed: ${it.describe()}" },
            )
        )
    }

    /**
     * Export without a document picker, for devices that have none. The
     * destination path is reported back because an unnamed "saved somewhere"
     * message is useless to someone who then has to go and find the file.
     */
    fun exportToDefaultLocation() = viewModelScope.launch {
        val file = backup.fallbackExportFile()
        _ui.value = _ui.value.copy(busyMessage = "Exporting…")
        val result = backup.exportToFile(file)
        _ui.value = _ui.value.copy(
            busyMessage = result.fold(
                onSuccess = { "Exported $it playlist(s) to ${file.absolutePath}" },
                onFailure = { "Export failed: ${it.describe()}" },
            )
        )
    }

    /** Attaches a login to an Xtream source that has none yet, then syncs it. */
    fun attachXtreamCredentials(id: Long, username: String, password: String) = viewModelScope.launch {
        sources.setXtreamCredentials(id, username, password)
        syncNow(id)
    }

    fun reportNoFilePicker(action: String) {
        _ui.value = _ui.value.copy(
            busyMessage = "This device has no file manager, so $action is not available here. " +
                "Use a playlist URL instead, or install a file manager from your app store.",
        )
    }

    fun import(source: Uri) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busyMessage = "Importing…")
        val result = backup.import(source)
        _ui.value = _ui.value.copy(
            busyMessage = result.fold(
                onSuccess = { "Imported $it playlist(s)." },
                onFailure = { "Import failed: ${it.describe()}" },
            )
        )
        result.getOrNull()?.let { if (it > 0) syncAll() }
    }

    fun clearBusyMessage() {
        _ui.value = _ui.value.copy(busyMessage = null)
    }

    private fun describeFailure(error: Throwable?): String = when ((error as? HttpFailure)?.kind) {
        HttpFailure.Kind.OFFLINE -> "Could not reach that server. Check the address and this device's network."
        HttpFailure.Kind.TIMEOUT -> "The server did not respond in time."
        HttpFailure.Kind.NOT_FOUND -> "That address does not look like an Xtream Codes panel (HTTP 404)."
        HttpFailure.Kind.FORBIDDEN -> "The server refused the connection."
        HttpFailure.Kind.SERVER_ERROR -> "The server returned an error."
        HttpFailure.Kind.CREDENTIALS_MISSING -> "No username or password is set for this playlist."
        else -> error?.describe() ?: "Unknown error"
    }
}
