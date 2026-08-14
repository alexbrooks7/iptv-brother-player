package com.iptv.player.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The persisted schema.
 *
 * These Room entities are used directly as the app's domain model rather than
 * being mapped into a parallel set of "clean architecture" data classes. With a
 * playlist of 10,000+ channels, an extra mapping pass is 10,000 extra objects
 * allocated on every list emission on a 1 GB Fire TV Stick, and it would buy
 * nothing: there is one persistence technology here and it is not changing.
 * Where a screen needs less than a whole row, there is a narrow @Query
 * projection instead (see [ChannelListItem]).
 */

enum class SourceType { M3U_URL, M3U_FILE, XTREAM }

enum class MediaKind { LIVE, MOVIE, SERIES }

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: SourceType,
    /** Playlist URL, `content://` document URI, or Xtream server base URL. */
    val url: String,
    /** Extra XMLTV URL. Merged with whatever the playlist itself carries. */
    val epgUrl: String? = null,
    /**
     * AES/GCM ciphertext of the Xtream username/password, produced by
     * [com.iptv.player.data.prefs.CredentialCrypto]. Never plaintext: the
     * brief requires Keystore-backed storage, and a leaked provider login is
     * the one piece of user data in this app that has real value to an
     * attacker.
     */
    val credentialsCipher: String? = null,
    val userAgent: String? = null,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val lastSyncAt: Long? = null,
    val lastSyncError: String? = null,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
)

@Entity(
    tableName = "categories",
    indices = [Index("sourceId", "kind")],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val kind: MediaKind,
    /** Provider's own id (Xtream `category_id`); null for M3U group titles. */
    val remoteId: String? = null,
    val name: String,
    val sortOrder: Int = 0,
    /**
     * Set by a name heuristic at import time (see CategoryClassifier). Drives
     * the parental-control filter. It is a guess, always overridable by the
     * user, and never used to hide anything unless the PIN lock is on.
     */
    val adult: Boolean = false,
)

@Entity(
    tableName = "channels",
    indices = [
        Index("sourceId", "categoryId"),
        Index("sourceId", "streamKey", unique = true),
        Index("tvgId"),
        Index("name"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val categoryId: Long? = null,
    /**
     * Stable identity of a channel *within a source*, used by favourites and
     * history so they survive a playlist refresh that renumbers everything.
     * Xtream: the stream id. M3U: tvg-id when present, else a hash of the
     * name + URL, because plenty of playlists ship no tvg-id at all.
     */
    val streamKey: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    /** XMLTV channel id — the join key to [ProgrammeEntity]. */
    val tvgId: String? = null,
    val groupTitle: String? = null,
    /** `tvg-chno` or Xtream `num`. Nullable: many playlists have no numbering. */
    val number: Int? = null,
    val sortOrder: Int = 0,
    val catchupDays: Int = 0,
    /** Provider-supplied catch-up URL template, when it differs from `url`. */
    val catchupSource: String? = null,
)

@Entity(
    tableName = "programmes",
    indices = [
        // Unique so that merging several EPG feeds for one playlist — which
        // the brief explicitly asks for — de-duplicates in the database rather
        // than in code: the second feed's copy of the same slot is dropped by
        // INSERT OR IGNORE. First feed listed wins on conflicting titles.
        Index("sourceId", "channelId", "startUtc", unique = true),
        Index("sourceId", "endUtc"),
    ],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    /** The XMLTV `channel` attribute; matched against [ChannelEntity.tvgId]. */
    val channelId: String,
    val title: String,
    val description: String? = null,
    val category: String? = null,
    val startUtc: Long,
    val endUtc: Long,
    val iconUrl: String? = null,
)

@Entity(
    tableName = "vod_items",
    indices = [Index("sourceId", "categoryId"), Index("sourceId", "streamKey", unique = true), Index("name")],
)
data class VodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val categoryId: Long? = null,
    val streamKey: String,
    val name: String,
    /**
     * Null for Xtream movies: the playable URL has to be built at play time
     * from the (decrypted) credentials, so it is never persisted. See
     * XtreamClient.streamUrl.
     */
    val url: String? = null,
    val containerExtension: String? = null,
    val poster: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val genre: String? = null,
    val plot: String? = null,
    val durationSecs: Int? = null,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "series",
    indices = [Index("sourceId", "categoryId"), Index("sourceId", "streamKey", unique = true), Index("name")],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val categoryId: Long? = null,
    val streamKey: String,
    val name: String,
    val poster: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val genre: String? = null,
    val plot: String? = null,
    val sortOrder: Int = 0,
    /**
     * Episodes are fetched lazily — `get_series_info` is one request *per
     * series*, so importing them all up front would mean thousands of requests
     * on a large VOD subscription. Null until the detail screen is opened.
     */
    val episodesLoadedAt: Long? = null,
)

@Entity(
    tableName = "episodes",
    indices = [Index("seriesRowId", "season", "episode"), Index("sourceId", "streamKey", unique = true)],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val seriesRowId: Long,
    val streamKey: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val plot: String? = null,
    val still: String? = null,
    val durationSecs: Int? = null,
    val containerExtension: String? = null,
    val url: String? = null,
)

@Entity(tableName = "favorites", primaryKeys = ["sourceId", "kind", "streamKey"])
data class FavoriteEntity(
    val sourceId: Long,
    val kind: MediaKind,
    val streamKey: String,
    val addedAt: Long,
)

/**
 * Doubles as "recently watched" and as the resume-position store. One row per
 * playable thing; live channels keep a position of 0 and are only used for the
 * recents row and for "open the last channel on start-up".
 */
@Entity(tableName = "watch_history", primaryKeys = ["sourceId", "kind", "streamKey"])
data class WatchHistoryEntity(
    val sourceId: Long,
    val kind: MediaKind,
    val streamKey: String,
    val title: String,
    val poster: String? = null,
    val lastWatchedAt: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** For episodes: which series this belongs to, so Continue Watching can
     *  show "Show name — S2E4" and deep-link back into the series. */
    val parentKey: String? = null,
    val parentTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)
