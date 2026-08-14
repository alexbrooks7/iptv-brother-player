package com.iptv.player.data.repo

import java.util.Locale

/**
 * Guesses whether a category name refers to adult content, so that the
 * parental PIN has something to lock on a freshly imported playlist.
 *
 * Both stores expect an app that *can* surface adult material from a
 * user-supplied playlist to ship working parental controls, and a control the
 * user has to configure from scratch across 300 categories is one nobody turns
 * on. So this pre-ticks the obvious ones.
 *
 * It is a guess and is treated as one: the flag is always editable in
 * Settings, and nothing is ever hidden unless the user has actually enabled
 * the PIN lock. False negatives are corrected by the user; false positives
 * (the classic being a category containing "Sussex" or "Essex") are avoided by
 * matching whole words only, never substrings.
 */
object CategoryClassifier {

    // Only tokens with no innocent reading. "Hot" and "Mature" are absent on
    // purpose — HOT is a mainstream Israeli broadcaster and "Mature" shows up
    // in drama categories, so both would lock legitimate content behind a PIN.
    private val EXACT_TOKENS = setOf(
        "xxx", "adult", "adults", "porn", "porno", "erotic", "erotik", "erotica",
        "18+", "21+", "playboy", "brazzers", "hustler", "nsfw",
    )

    /** Phrases that only mean one thing, checked against the whole name. */
    private val PHRASES = listOf(
        "for adults", "adult only", "adults only", "adult channels", "adult movies",
        "18 plus", "over 18", "red light", "night club", "late night xxx",
    )

    fun isAdult(categoryName: String): Boolean {
        val lower = categoryName.lowercase(Locale.US)
        if (PHRASES.any { lower.contains(it) }) return true

        // Split on everything that is not a letter, digit or '+' so that
        // "XXX", "|XXX|", "18+" and "» Adult «" all reduce to clean tokens.
        return lower.split(SEPARATORS).any { token ->
            token.isNotEmpty() && token in EXACT_TOKENS
        }
    }

    private val SEPARATORS = Regex("[^a-z0-9+]+")
}
