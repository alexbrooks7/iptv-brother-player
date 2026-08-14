package com.iptv.player.ui.nav

import androidx.annotation.StringRes
import com.iptv.player.R

/**
 * Top-level sections, in the order they appear in the side navigation.
 *
 * Live TV comes first rather than Home because it is what the overwhelming
 * majority of sessions are for; a user who opens this app is usually trying to
 * get to a channel, not to browse. Home stays available for continue-watching
 * and favourites, but it does not sit between the user and the TV.
 */
enum class Section(val route: String, @StringRes val labelRes: Int, val glyph: String) {
    Live("live", R.string.nav_live, "▶"),
    Guide("guide", R.string.nav_guide, "▤"),
    Home("home", R.string.nav_home, "★"),
    Movies("movies", R.string.nav_movies, "▣"),
    Series("series", R.string.nav_series, "▦"),
    Search("search", R.string.nav_search, "⌕"),
    Sources("sources", R.string.nav_sources, "≡"),
    Settings("settings", R.string.nav_settings, "⚙"),
}

/** Routes that are not sections of the side navigation. */
object Routes {
    const val ADD_SOURCE = "sources/add"
    const val MOVIE_DETAIL = "movies/{movieId}"
    const val SERIES_DETAIL = "series/{seriesId}"
    const val PLAYER = "player"

    fun movieDetail(id: Long) = "movies/$id"
    fun seriesDetail(id: Long) = "series/$id"
}
