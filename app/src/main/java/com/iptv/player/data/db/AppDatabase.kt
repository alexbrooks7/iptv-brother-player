package com.iptv.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        SourceEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        ProgrammeEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        FavoriteEntity::class,
        WatchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun programmes(): ProgrammeDao
    abstract fun vod(): VodDao
    abstract fun series(): SeriesDao
    abstract fun episodes(): EpisodeDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): WatchHistoryDao

    companion object {
        const val NAME = "iptv.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                // WAL keeps the guide-refresh worker's bulk writes from blocking
                // the reads the UI is doing on the same database — without it,
                // a background XMLTV import visibly stalls channel browsing.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // No destructive-migration fallback on purpose: favourites,
                // watch history and resume positions are user data that cannot
                // be re-derived from the provider. Schema changes from v2
                // onward must ship a real Migration; the exported schemas in
                // app/schemas are what those get written against.
                .build()
    }
}
