package com.iptv.player.data.db

import androidx.room.TypeConverter

/**
 * Enums are persisted by `name`, not by ordinal. Ordinals would silently
 * re-map every stored row the first time someone inserts a new constant in the
 * middle of an enum, and the names are also what the hand-written `WHERE kind =
 * 'LIVE'` clauses in the DAOs compare against.
 */
class Converters {
    @TypeConverter fun sourceTypeToString(value: SourceType): String = value.name

    @TypeConverter
    fun stringToSourceType(value: String): SourceType =
        runCatching { SourceType.valueOf(value) }.getOrDefault(SourceType.M3U_URL)

    @TypeConverter fun mediaKindToString(value: MediaKind): String = value.name

    @TypeConverter
    fun stringToMediaKind(value: String): MediaKind =
        runCatching { MediaKind.valueOf(value) }.getOrDefault(MediaKind.LIVE)
}
