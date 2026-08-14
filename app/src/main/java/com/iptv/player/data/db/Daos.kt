package com.iptv.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY sortOrder, id")
    suspend fun getAll(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: Long): SourceEntity?

    @Query("SELECT * FROM sources WHERE id = :id")
    fun observeById(id: Long): Flow<SourceEntity?>

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM sources")
    suspend fun maxSortOrder(): Int

    @Query("UPDATE sources SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    @Query("UPDATE sources SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query(
        """UPDATE sources SET liveCount = :live, movieCount = :movies, seriesCount = :series,
           lastSyncAt = :at, lastSyncError = NULL WHERE id = :id"""
    )
    suspend fun markSynced(id: Long, live: Int, movies: Int, series: Int, at: Long)

    @Query("UPDATE sources SET lastSyncError = :error WHERE id = :id")
    suspend fun markSyncFailed(id: Long, error: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE sourceId = :sourceId AND kind = :kind ORDER BY sortOrder, name")
    fun observe(sourceId: Long, kind: MediaKind): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE sourceId = :sourceId AND kind = :kind ORDER BY sortOrder, name")
    suspend fun get(sourceId: Long, kind: MediaKind): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CategoryEntity>): List<Long>

    @Query("UPDATE categories SET adult = :adult WHERE id = :id")
    suspend fun setAdult(id: Long, adult: Boolean)

    @Query("SELECT id FROM categories WHERE sourceId = :sourceId AND adult = 1")
    suspend fun adultCategoryIds(sourceId: Long): List<Long>

    @Query("SELECT id FROM categories WHERE adult = 1")
    fun observeAdultCategoryIds(): Flow<List<Long>>

    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND kind = :kind")
    suspend fun deleteFor(sourceId: Long, kind: MediaKind)
}

/** Narrow projection for the guide, which only needs to label its rows. */
data class GuideChannel(
    val id: Long,
    val streamKey: String,
    val name: String,
    val logo: String?,
    val tvgId: String?,
    val number: Int?,
)

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE sourceId = :sourceId ORDER BY sortOrder, id")
    fun observeAll(sourceId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND categoryId = :categoryId ORDER BY sortOrder, id")
    fun observeByCategory(sourceId: Long, categoryId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND categoryId IS NULL ORDER BY sortOrder, id")
    fun observeUncategorised(sourceId: Long): Flow<List<ChannelEntity>>

    @Query(
        """SELECT c.* FROM channels c
           INNER JOIN favorites f ON f.sourceId = c.sourceId AND f.streamKey = c.streamKey AND f.kind = 'LIVE'
           WHERE c.sourceId = :sourceId ORDER BY f.addedAt DESC"""
    )
    fun observeFavorites(sourceId: Long): Flow<List<ChannelEntity>>

    @Query(
        """SELECT c.* FROM channels c
           INNER JOIN watch_history h ON h.sourceId = c.sourceId AND h.streamKey = c.streamKey AND h.kind = 'LIVE'
           WHERE c.sourceId = :sourceId ORDER BY h.lastWatchedAt DESC LIMIT :limit"""
    )
    fun observeRecent(sourceId: Long, limit: Int = 20): Flow<List<ChannelEntity>>

    @Query("SELECT id, streamKey, name, logo, tvgId, number FROM channels WHERE sourceId = :sourceId ORDER BY sortOrder, id")
    fun observeForGuide(sourceId: Long): Flow<List<GuideChannel>>

    // `\` escapes the LIKE wildcards so a channel search for "100%" does not
    // match everything. SQLite needs the ESCAPE clause spelled out.
    @Query(
        """SELECT * FROM channels WHERE sourceId = :sourceId AND name LIKE '%' || :query || '%' ESCAPE '\'
           ORDER BY sortOrder, id LIMIT :limit"""
    )
    suspend fun search(sourceId: Long, query: String, limit: Int = 100): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND streamKey = :streamKey")
    suspend fun getByKey(sourceId: Long, streamKey: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun getById(id: Long): ChannelEntity?

    @Query("SELECT COUNT(*) FROM channels WHERE sourceId = :sourceId")
    suspend fun count(sourceId: Long): Int

    @Query("SELECT DISTINCT tvgId FROM channels WHERE sourceId = :sourceId AND tvgId IS NOT NULL")
    suspend fun tvgIds(sourceId: Long): List<String>

    @Query("SELECT id, streamKey, name, logo, tvgId, number FROM channels WHERE sourceId = :sourceId AND (tvgId IS NULL OR tvgId = '')")
    suspend fun withoutTvgId(sourceId: Long): List<GuideChannel>

    @Query("UPDATE channels SET tvgId = :tvgId WHERE id = :id")
    suspend fun setTvgId(id: Long, tvgId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteFor(sourceId: Long)
}

@Dao
interface ProgrammeDao {
    /**
     * Everything overlapping the visible window for the visible channels. The
     * `start < to AND end > from` form (rather than `start BETWEEN`) is what
     * makes a programme that began before the window still show up — otherwise
     * the guide is blank for whatever is currently on air.
     */
    @Query(
        """SELECT * FROM programmes
           WHERE sourceId = :sourceId AND channelId IN (:channelIds) AND startUtc < :to AND endUtc > :from
           ORDER BY channelId, startUtc"""
    )
    suspend fun inWindow(sourceId: Long, channelIds: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    @Query(
        """SELECT * FROM programmes WHERE sourceId = :sourceId AND channelId = :channelId AND endUtc > :now
           ORDER BY startUtc LIMIT :limit"""
    )
    suspend fun upcoming(sourceId: Long, channelId: String, now: Long, limit: Int = 2): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programmes WHERE sourceId = :sourceId")
    suspend fun count(sourceId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE sourceId = :sourceId")
    suspend fun deleteFor(sourceId: Long)

    /**
     * Housekeeping: yesterday's listings are dead weight on a 1 GB box.
     *
     * Scoped to one source so the `(sourceId, endUtc)` index can serve it. The
     * previous form filtered on `endUtc` alone, and because that is not the
     * leading column of any index, SQLite could only answer it with a full
     * scan of the programme table — which on the profiled device meant scanning
     * 336,000 rows to delete 233,000 of them. Callers loop over sources.
     */
    @Query("DELETE FROM programmes WHERE sourceId = :sourceId AND endUtc < :cutoff")
    suspend fun deleteEndedBefore(sourceId: Long, cutoff: Long)

    @Query("SELECT COUNT(*) FROM programmes WHERE sourceId = :sourceId AND endUtc < :cutoff")
    suspend fun countEndedBefore(sourceId: Long, cutoff: Long): Int
}

@Dao
interface VodDao {
    @Query("SELECT * FROM vod_items WHERE sourceId = :sourceId AND categoryId = :categoryId ORDER BY sortOrder, id")
    fun observeByCategory(sourceId: Long, categoryId: Long): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod_items WHERE sourceId = :sourceId ORDER BY sortOrder, id")
    fun observeAll(sourceId: Long): Flow<List<VodEntity>>

    @Query(
        """SELECT * FROM vod_items WHERE sourceId = :sourceId AND name LIKE '%' || :query || '%' ESCAPE '\'
           ORDER BY sortOrder, id LIMIT :limit"""
    )
    suspend fun search(sourceId: Long, query: String, limit: Int = 60): List<VodEntity>

    @Query("SELECT * FROM vod_items WHERE sourceId = :sourceId AND streamKey = :streamKey")
    suspend fun getByKey(sourceId: Long, streamKey: String): VodEntity?

    @Query("SELECT * FROM vod_items WHERE id = :id")
    suspend fun getById(id: Long): VodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<VodEntity>)

    @Query("UPDATE vod_items SET plot = :plot, year = :year, rating = :rating, genre = :genre, durationSecs = :duration, poster = COALESCE(:poster, poster) WHERE id = :id")
    suspend fun enrich(id: Long, plot: String?, year: String?, rating: String?, genre: String?, duration: Int?, poster: String?)

    @Query("DELETE FROM vod_items WHERE sourceId = :sourceId")
    suspend fun deleteFor(sourceId: Long)
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND categoryId = :categoryId ORDER BY sortOrder, id")
    fun observeByCategory(sourceId: Long, categoryId: Long): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE sourceId = :sourceId ORDER BY sortOrder, id")
    fun observeAll(sourceId: Long): Flow<List<SeriesEntity>>

    @Query(
        """SELECT * FROM series WHERE sourceId = :sourceId AND name LIKE '%' || :query || '%' ESCAPE '\'
           ORDER BY sortOrder, id LIMIT :limit"""
    )
    suspend fun search(sourceId: Long, query: String, limit: Int = 60): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getById(id: Long): SeriesEntity?

    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND streamKey = :streamKey")
    suspend fun getByKey(sourceId: Long, streamKey: String): SeriesEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SeriesEntity>)

    @Query("UPDATE series SET episodesLoadedAt = :at WHERE id = :id")
    suspend fun markEpisodesLoaded(id: Long, at: Long)

    @Query("DELETE FROM series WHERE sourceId = :sourceId")
    suspend fun deleteFor(sourceId: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE seriesRowId = :seriesRowId ORDER BY season, episode")
    fun observeForSeries(seriesRowId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE seriesRowId = :seriesRowId ORDER BY season, episode")
    suspend fun forSeries(seriesRowId: Long): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND streamKey = :streamKey")
    suspend fun getByKey(sourceId: Long, streamKey: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE seriesRowId = :seriesRowId")
    suspend fun deleteForSeries(seriesRowId: Long)

    @Query("DELETE FROM episodes WHERE sourceId = :sourceId")
    suspend fun deleteFor(sourceId: Long)
}

@Dao
interface FavoriteDao {
    @Query("SELECT streamKey FROM favorites WHERE sourceId = :sourceId AND kind = :kind")
    fun observeKeys(sourceId: Long, kind: MediaKind): Flow<List<String>>

    @Query("SELECT streamKey FROM favorites WHERE sourceId = :sourceId AND kind = :kind ORDER BY addedAt")
    suspend fun keysOnce(sourceId: Long, kind: MediaKind): List<String>

    @Query("SELECT COUNT(*) > 0 FROM favorites WHERE sourceId = :sourceId AND kind = :kind AND streamKey = :streamKey")
    suspend fun isFavorite(sourceId: Long, kind: MediaKind, streamKey: String): Boolean

    @Upsert
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId AND kind = :kind AND streamKey = :streamKey")
    suspend fun remove(sourceId: Long, kind: MediaKind, streamKey: String)

    @Transaction
    suspend fun toggle(sourceId: Long, kind: MediaKind, streamKey: String, now: Long): Boolean {
        return if (isFavorite(sourceId, kind, streamKey)) {
            remove(sourceId, kind, streamKey)
            false
        } else {
            add(FavoriteEntity(sourceId, kind, streamKey, now))
            true
        }
    }
}

@Dao
interface WatchHistoryDao {
    /**
     * Continue Watching: anything with a real position that is neither barely
     * started nor effectively finished. The 3%/92% bounds keep the row from
     * filling with things the user opened by accident or already saw the
     * credits of.
     */
    @Query(
        """SELECT * FROM watch_history
           WHERE kind != 'LIVE' AND durationMs > 0
             AND positionMs > durationMs * 0.03 AND positionMs < durationMs * 0.92
           ORDER BY lastWatchedAt DESC LIMIT :limit"""
    )
    fun observeContinueWatching(limit: Int = 20): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE sourceId = :sourceId AND kind = :kind AND streamKey = :streamKey")
    suspend fun get(sourceId: Long, kind: MediaKind, streamKey: String): WatchHistoryEntity?

    @Query("SELECT * FROM watch_history WHERE kind = 'LIVE' ORDER BY lastWatchedAt DESC LIMIT 1")
    suspend fun lastLiveChannel(): WatchHistoryEntity?

    @Upsert
    suspend fun upsert(entry: WatchHistoryEntity)

    @Query("UPDATE watch_history SET positionMs = :position, durationMs = :duration, lastWatchedAt = :at WHERE sourceId = :sourceId AND kind = :kind AND streamKey = :streamKey")
    suspend fun updatePosition(sourceId: Long, kind: MediaKind, streamKey: String, position: Long, duration: Long, at: Long)

    @Query("DELETE FROM watch_history")
    suspend fun clear()

    @Query("DELETE FROM watch_history WHERE sourceId = :sourceId")
    suspend fun clearFor(sourceId: Long)
}
