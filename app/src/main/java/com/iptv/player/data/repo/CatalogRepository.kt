package com.iptv.player.data.repo

import com.iptv.player.data.db.AppDatabase
import com.iptv.player.data.db.CategoryEntity
import com.iptv.player.data.db.ChannelEntity
import com.iptv.player.data.db.EpisodeEntity
import com.iptv.player.data.db.GuideChannel
import com.iptv.player.data.db.MediaKind
import com.iptv.player.data.db.SeriesEntity
import com.iptv.player.data.db.SourceEntity
import com.iptv.player.data.db.SourceType
import com.iptv.player.data.db.VodEntity
import com.iptv.player.data.db.WatchHistoryEntity
import com.iptv.player.data.remote.asInt
import com.iptv.player.data.remote.asObject
import com.iptv.player.data.remote.asString
import com.iptv.player.data.remote.firstOf
import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A search hit, whatever kind of thing it is. */
data class SearchResult(
    val kind: MediaKind,
    val id: Long,
    val title: String,
    val subtitle: String?,
    val artwork: String?,
)

/**
 * Read-side access to an imported catalogue, plus favourites and watch
 * history. Writes that come from the *provider* live in [CatalogImporter];
 * writes that come from the *user* live here.
 */
class CatalogRepository(
    private val db: AppDatabase,
    private val sources: SourceRepository,
) {

    // ---- Categories and channels ---------------------------------------

    fun observeCategories(sourceId: Long, kind: MediaKind): Flow<List<CategoryEntity>> =
        db.categories().observe(sourceId, kind)

    fun observeChannels(sourceId: Long): Flow<List<ChannelEntity>> = db.channels().observeAll(sourceId)

    fun observeChannels(sourceId: Long, categoryId: Long): Flow<List<ChannelEntity>> =
        db.channels().observeByCategory(sourceId, categoryId)

    fun observeUncategorisedChannels(sourceId: Long): Flow<List<ChannelEntity>> =
        db.channels().observeUncategorised(sourceId)

    fun observeFavoriteChannels(sourceId: Long): Flow<List<ChannelEntity>> =
        db.channels().observeFavorites(sourceId)

    fun observeRecentChannels(sourceId: Long): Flow<List<ChannelEntity>> =
        db.channels().observeRecent(sourceId)

    fun observeGuideChannels(sourceId: Long): Flow<List<GuideChannel>> =
        db.channels().observeForGuide(sourceId)

    suspend fun channel(sourceId: Long, streamKey: String): ChannelEntity? =
        withContext(Dispatchers.IO) { db.channels().getByKey(sourceId, streamKey) }

    suspend fun channelById(id: Long): ChannelEntity? = withContext(Dispatchers.IO) { db.channels().getById(id) }

    // ---- VOD and series -------------------------------------------------

    fun observeMovies(sourceId: Long): Flow<List<VodEntity>> = db.vod().observeAll(sourceId)

    fun observeMovies(sourceId: Long, categoryId: Long): Flow<List<VodEntity>> =
        db.vod().observeByCategory(sourceId, categoryId)

    fun observeSeries(sourceId: Long): Flow<List<SeriesEntity>> = db.series().observeAll(sourceId)

    fun observeSeries(sourceId: Long, categoryId: Long): Flow<List<SeriesEntity>> =
        db.series().observeByCategory(sourceId, categoryId)

    fun observeEpisodes(seriesRowId: Long): Flow<List<EpisodeEntity>> =
        db.episodes().observeForSeries(seriesRowId)

    suspend fun movie(id: Long): VodEntity? = withContext(Dispatchers.IO) { db.vod().getById(id) }

    suspend fun movieByKey(sourceId: Long, streamKey: String): VodEntity? =
        withContext(Dispatchers.IO) { db.vod().getByKey(sourceId, streamKey) }

    suspend fun series(id: Long): SeriesEntity? = withContext(Dispatchers.IO) { db.series().getById(id) }

    suspend fun episode(sourceId: Long, streamKey: String): EpisodeEntity? =
        withContext(Dispatchers.IO) { db.episodes().getByKey(sourceId, streamKey) }

    /**
     * Fetches a series' episodes on first open and caches them.
     *
     * Deliberately lazy: `get_series_info` is one HTTP request per series, so
     * importing episodes for a 4,000-series catalogue up front would mean
     * 4,000 requests and a refresh measured in hours. [maxAgeMillis] keeps the
     * cache fresh enough to pick up newly added episodes of a running show
     * without re-fetching on every visit.
     */
    suspend fun ensureEpisodes(
        source: SourceEntity,
        series: SeriesEntity,
        maxAgeMillis: Long = 12 * 60 * 60 * 1000L,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val loadedAt = series.episodesLoadedAt
        if (loadedAt != null && System.currentTimeMillis() - loadedAt < maxAgeMillis) return@withContext Result.success(Unit)
        if (source.type != SourceType.XTREAM) return@withContext Result.success(Unit)

        runCatching {
            val client = sources.client(source) ?: error("No credentials for ${source.name}")
            val info = client.seriesInfo(series.streamKey) ?: error("Empty series info")
            val episodes = parseEpisodes(info, source.id, series.id)
            db.episodes().deleteForSeries(series.id)
            if (episodes.isNotEmpty()) db.episodes().insertAll(episodes)
            db.series().markEpisodesLoaded(series.id, System.currentTimeMillis())
            Diagnostics.info("series", "${series.name}: ${episodes.size} episodes")
        }.onFailure { Diagnostics.error("series", "Could not load episodes for ${series.name}", it) }
    }

    /**
     * `episodes` is an object keyed by season number, each holding an array of
     * episodes — not an array of seasons, which is what the field name
     * suggests. Season numbers arrive as the object's keys and, inconsistently,
     * also inside each episode.
     */
    private fun parseEpisodes(info: JsonObject, sourceId: Long, seriesRowId: Long): List<EpisodeEntity> {
        val seasons = info["episodes"]?.asObject() ?: return emptyList()
        val out = ArrayList<EpisodeEntity>(64)
        seasons.forEach { (seasonKey, value) ->
            val list = value as? JsonArray ?: return@forEach
            val seasonNumber = seasonKey.toIntOrNull() ?: 0
            list.forEach { element ->
                val obj = element.asObject() ?: return@forEach
                val id = obj.firstOf("id", "stream_id")?.asString() ?: return@forEach
                val detail = obj["info"]?.asObject()
                out += EpisodeEntity(
                    sourceId = sourceId,
                    seriesRowId = seriesRowId,
                    streamKey = id,
                    season = obj.firstOf("season")?.asInt() ?: seasonNumber,
                    episode = obj.firstOf("episode_num", "episode")?.asInt() ?: (out.size + 1),
                    title = obj.firstOf("title", "name")?.asString() ?: "Episode ${out.size + 1}",
                    plot = detail?.firstOf("plot", "description")?.asString(),
                    still = detail?.firstOf("movie_image", "cover_big", "still_path")?.asString(),
                    durationSecs = detail?.firstOf("duration_secs")?.asInt(),
                    containerExtension = obj.firstOf("container_extension")?.asString(),
                    url = null,
                )
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /** Fills in plot/rating/duration for a movie the list view only had a name for. */
    suspend fun enrichMovie(source: SourceEntity, movie: VodEntity): VodEntity =
        withContext(Dispatchers.IO) {
            if (source.type != SourceType.XTREAM || !movie.plot.isNullOrBlank()) return@withContext movie
            runCatching {
                val client = sources.client(source) ?: return@withContext movie
                val info = client.vodInfo(movie.streamKey)?.get("info")?.asObject() ?: return@withContext movie
                db.vod().enrich(
                    id = movie.id,
                    plot = info.firstOf("plot", "description")?.asString(),
                    year = info.firstOf("releasedate", "release_date", "year")?.asString()?.take(4),
                    rating = info.firstOf("rating")?.asString(),
                    genre = info.firstOf("genre")?.asString(),
                    duration = info.firstOf("duration_secs")?.asInt(),
                    poster = info.firstOf("movie_image", "cover_big")?.asString(),
                )
                db.vod().getById(movie.id) ?: movie
            }.getOrDefault(movie)
        }

    // ---- Search ---------------------------------------------------------

    /**
     * One query across all three catalogues.
     *
     * The `%` and `_` wildcards are escaped before the term reaches SQL —
     * without it, searching for "100%" matches the entire playlist, which
     * looks like the search is broken.
     */
    suspend fun search(sourceId: Long, rawQuery: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val query = rawQuery.trim()
        if (query.length < 2) return@withContext emptyList()
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        buildList {
            db.channels().search(sourceId, escaped).forEach {
                add(SearchResult(MediaKind.LIVE, it.id, it.name, it.groupTitle, it.logo))
            }
            db.vod().search(sourceId, escaped).forEach {
                add(SearchResult(MediaKind.MOVIE, it.id, it.name, it.year, it.poster))
            }
            db.series().search(sourceId, escaped).forEach {
                add(SearchResult(MediaKind.SERIES, it.id, it.name, it.year, it.poster))
            }
        }
    }

    // ---- Favourites -----------------------------------------------------

    fun observeFavoriteKeys(sourceId: Long, kind: MediaKind): Flow<List<String>> =
        db.favorites().observeKeys(sourceId, kind)

    suspend fun toggleFavorite(sourceId: Long, kind: MediaKind, streamKey: String): Boolean =
        withContext(Dispatchers.IO) {
            db.favorites().toggle(sourceId, kind, streamKey, System.currentTimeMillis())
        }

    // ---- History and resume ---------------------------------------------

    fun observeContinueWatching(): Flow<List<WatchHistoryEntity>> = db.history().observeContinueWatching()

    suspend fun lastLiveChannel(): WatchHistoryEntity? = withContext(Dispatchers.IO) { db.history().lastLiveChannel() }

    suspend fun resumePosition(sourceId: Long, kind: MediaKind, streamKey: String): Long =
        withContext(Dispatchers.IO) { db.history().get(sourceId, kind, streamKey)?.positionMs ?: 0 }

    suspend fun recordOpened(stream: PlayableStream, parentTitle: String? = null, parentKey: String? = null, season: Int? = null, episode: Int? = null) =
        withContext(Dispatchers.IO) {
            val existing = db.history().get(stream.sourceId, stream.kind, stream.streamKey)
            db.history().upsert(
                WatchHistoryEntity(
                    sourceId = stream.sourceId,
                    kind = stream.kind,
                    streamKey = stream.streamKey,
                    title = stream.title,
                    poster = stream.artwork,
                    lastWatchedAt = System.currentTimeMillis(),
                    positionMs = existing?.positionMs ?: 0,
                    durationMs = existing?.durationMs ?: 0,
                    parentKey = parentKey ?: existing?.parentKey,
                    parentTitle = parentTitle ?: existing?.parentTitle,
                    season = season ?: existing?.season,
                    episode = episode ?: existing?.episode,
                )
            )
        }

    /**
     * Stores a resume point. Live channels are excluded — a position into a
     * live stream means nothing, and writing one on every tick would churn the
     * database during normal TV watching for no benefit.
     */
    suspend fun savePosition(stream: PlayableStream, positionMs: Long, durationMs: Long) =
        withContext(Dispatchers.IO) {
            if (stream.kind == MediaKind.LIVE || durationMs <= 0) return@withContext
            db.history().updatePosition(
                sourceId = stream.sourceId,
                kind = stream.kind,
                streamKey = stream.streamKey,
                position = positionMs,
                duration = durationMs,
                at = System.currentTimeMillis(),
            )
        }

    suspend fun clearHistory() = withContext(Dispatchers.IO) { db.history().clear() }
}
