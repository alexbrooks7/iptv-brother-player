package com.iptv.player.data.db

import com.iptv.player.util.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Returns freed database pages to the filesystem.
 *
 * Deleting rows in SQLite does not shrink the file: the pages go on a free
 * list and are reused by later inserts, so a database that ballooned once
 * stays that size on disk forever. That is usually the right trade — the space
 * gets reused and a rewrite is expensive. It stops being the right trade when
 * the bloat is a one-off that will never be reused at the same scale, which is
 * exactly the shape of this app's problem: the guide table grew to 156 MB on a
 * 2 GB box because expired listings were never removed, and once they are,
 * roughly 110 MB of that is free list that steady-state operation will never
 * touch again.
 *
 * VACUUM is the only way to hand it back, and it is expensive enough to be
 * worth gating carefully — it rewrites the entire database and needs room for
 * a second copy while it does. Hence both guards below.
 */
object DatabaseMaintenance {

    /** Vacuum only when this much of the file is free space. */
    private const val FREE_PAGE_RATIO_THRESHOLD = 0.20

    /** Below this, the file is small enough that the rewrite is not worth it. */
    private const val MIN_INTERESTING_BYTES = 24L * 1024 * 1024

    /**
     * Compacts the database if it is worth doing and safe to do.
     *
     * Returns the number of bytes reclaimed, or 0 if it declined to run. Never
     * throws: this is housekeeping, and a device that will not vacuum should
     * carry on with a large file rather than fail whatever called this.
     */
    suspend fun reclaimSpaceIfWorthwhile(db: AppDatabase, databaseFile: File): Long =
        withContext(Dispatchers.IO) {
            runCatching {
                val sizeBefore = databaseFile.length()
                if (sizeBefore < MIN_INTERESTING_BYTES) return@runCatching 0L

                val pageSize = db.pragmaLong("page_size")
                val pageCount = db.pragmaLong("page_count")
                val freeList = db.pragmaLong("freelist_count")
                if (pageSize <= 0 || pageCount <= 0) return@runCatching 0L

                val freeRatio = freeList.toDouble() / pageCount
                if (freeRatio < FREE_PAGE_RATIO_THRESHOLD) return@runCatching 0L

                // VACUUM writes a complete second copy before swapping. On a TV
                // box with 8 GB of storage that is a real constraint, and
                // running out mid-vacuum is a far worse outcome than a big file.
                val required = sizeBefore * 2
                val usable = databaseFile.parentFile?.usableSpace ?: 0L
                if (usable < required) {
                    Diagnostics.warn(
                        "db",
                        "Skipping compaction: needs ${required / 1_048_576} MB free, has ${usable / 1_048_576} MB",
                    )
                    return@runCatching 0L
                }

                Diagnostics.info(
                    "db",
                    "Compacting database: ${sizeBefore / 1_048_576} MB, ${(freeRatio * 100).toInt()}% free pages",
                )
                db.openHelper.writableDatabase.execSQL("VACUUM")

                val reclaimed = sizeBefore - databaseFile.length()
                Diagnostics.info("db", "Compaction reclaimed ${reclaimed / 1_048_576} MB")
                reclaimed
            }.getOrElse {
                Diagnostics.error("db", "Compaction failed", it)
                0L
            }
        }

    private fun AppDatabase.pragmaLong(name: String): Long =
        openHelper.readableDatabase.query("PRAGMA $name").use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        }
}
