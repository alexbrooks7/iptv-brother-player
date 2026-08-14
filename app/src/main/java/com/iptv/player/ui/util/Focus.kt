package com.iptv.player.ui.util

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer

/**
 * D-pad focus helpers.
 *
 * The brief singles out focus behaviour: "Fast, responsive focus transitions
 * (laggy focus movement is the #1 complaint in TV app reviews)". Most of that
 * is avoiding heavy recomposition on focus change, which is handled at the
 * call sites. What is centralised here is the other half — focus never being
 * *lost*. On a remote there is no way to recover from a screen with nothing
 * focused: every press does nothing and the app appears frozen.
 */

/**
 * Marks a container as a focus group, so D-pad movement treats its children as
 * one unit and focus search does not wander into it sideways.
 *
 * **This deliberately does not use `Modifier.focusRestorer()`, which it used to.**
 *
 * The restorer is the obvious thing to reach for — it remembers which child was
 * focused and returns to it, so leaving the channel list and coming back lands
 * on the channel you were on rather than the top of the list. It is also, in
 * this Compose version, the direct cause of the worst bug a TV app can have.
 *
 * Observed on device with two adjacent restorer groups, which is exactly the
 * Live screen's category column beside its channel column: pressing left out of
 * the channel list left *nothing at all* focused. Verified with `uiautomator
 * dump` — the count of nodes reporting `focused="true"` went from one to zero on
 * that single keypress, and stayed zero. There is no way back from that with a
 * remote: every subsequent button does nothing and the app looks frozen. That is
 * what "the selection cursor just disappears" was.
 *
 * Its `onRestoreFailed` parameter looks like the fix and is worse. Pointing it
 * at a row inside a lazy list crashed outright with `IllegalStateException:
 * Release should only be called once` — the restorer pins the item it intends
 * to restore, and the failure path can release that pin twice.
 *
 * Plain `focusGroup()` behaves well because Compose's focus search is
 * geometric: moving left from a channel row lands on the category row at
 * roughly the same height, and moving right returns to a channel at roughly the
 * same height. For a two-column layout that is what a viewer expects anyway, so
 * almost nothing is lost. Where returning to an exact item genuinely matters,
 * do it explicitly with a remembered key and a FocusRequester rather than
 * reintroducing this.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tvFocusGroup(): Modifier = this.focusGroup()

/**
 * Requests focus, retrying across frames until the node exists.
 *
 * `requestFocus()` on a node that has not been placed yet fails, and a
 * `LaunchedEffect` frequently runs before layout has happened — especially
 * when the target was only just added to the composition, which is exactly the
 * case for a field switching into edit mode or a newly opened screen. A single
 * attempt therefore silently does nothing, and the symptom is the classic
 * "nothing is selected and the remote does nothing" state, from which a TV
 * user has no way to recover.
 *
 * Waiting for real frames rather than sleeping a fixed delay means this
 * resolves on the first frame in the common case and still copes with a slow
 * box that takes several.
 */
suspend fun FocusRequester.requestFocusWhenReady(attempts: Int = 8) {
    repeat(attempts) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess) return
    }
}

/**
 * Requests focus once the composition is laid out, and only if [enabled].
 */
@Composable
fun rememberInitialFocus(enabled: Boolean = true): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(enabled) {
        if (enabled) requester.requestFocusWhenReady()
    }
    return requester
}

/** Applies a [FocusRequester] and makes the node the group's entry point. */
@Composable
fun Modifier.initialFocus(requester: FocusRequester): Modifier =
    this.focusRequester(requester)

/**
 * Stops D-pad presses from escaping a container in a given direction by
 * pinning the movement back onto the container itself.
 *
 * Used on the player overlay: pressing left from the channel list should stay
 * put rather than silently moving focus to something invisible underneath.
 */
@Composable
fun Modifier.trapFocus(
    left: Boolean = false,
    right: Boolean = false,
    up: Boolean = false,
    down: Boolean = false,
): Modifier {
    val self = remember { FocusRequester() }
    return this
        .focusRequester(self)
        .focusProperties {
            if (left) this.left = self
            if (right) this.right = self
            if (up) this.up = self
            if (down) this.down = self
        }
}
