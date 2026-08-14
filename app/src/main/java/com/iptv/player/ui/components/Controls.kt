package com.iptv.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.iptv.player.ui.util.rememberInitialFocus
import com.iptv.player.ui.util.requestFocusWhenReady

// A wide, near-pill radius rather than the 8–10dp a phone control would use.
// TV rows sit further apart and are read from across a room, where a subtle
// corner rounding is invisible; a rounding this generous is what makes a row
// of buttons or chips look like a family of shapes rather than a grid of
// rectangles someone forgot to finish.
private val ControlShape = RoundedCornerShape(18.dp)

/**
 * The app's only button.
 *
 * Focus is signalled by *both* a fill change and a border, not by one alone.
 * TVs are frequently mis-calibrated and often sit in a bright room; a subtle
 * elevation or tint change that reads clearly on a monitor can be invisible
 * across a living room, and the D-pad gives no other cue about what is
 * selected. The border is always the orange accent — see the class doc on
 * `AccentOrange` in Color.kt for why that colour and no other is used for
 * focus.
 */
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    autoFocus: Boolean = false,
    contentDescription: String? = null,
) {
    val focusRequester = rememberInitialFocus(enabled = autoFocus)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .then(if (autoFocus) Modifier.focusRequester(focusRequester) else Modifier)
            .semantics { contentDescription?.let { this.contentDescription = it } },
        shape = ClickableSurfaceDefaults.shape(ControlShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                shape = ControlShape,
            )
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

/**
 * Text input for a TV.
 *
 * **Two modes, because a TV IME cannot share the D-pad.** A Compose text field
 * asks for the keyboard as soon as it takes focus, and while that keyboard is
 * up it owns every arrow press — so a form built the phone way becomes a trap:
 * focus lands in the first field, the IME opens over the screen, and the next
 * Down goes to the letter "z" instead of the next field. Moving through five
 * fields means opening and dismissing the keyboard five times.
 *
 * So the field is normally a plain focusable row showing its current value.
 * Arrow keys walk past it like any other control, and only a centre-press
 * switches it into an editing state that requests focus and brings up the IME.
 * Done, Back, or a vertical press ends editing and hands the D-pad back. This
 * is how set-top boxes and the established TV players behave, and it is the
 * difference between a form that takes ten presses and one that takes thirty.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    autoFocus: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    // Distinguishes "never edited" from "just finished editing", so the field
    // does not steal focus on first composition.
    var everEdited by remember { mutableStateOf(false) }
    // Where focus should go after edit mode closes, if it was closed by a
    // directional press rather than by Back or Done.
    var pendingExitDirection by remember { mutableStateOf<FocusDirection?>(null) }
    var focused by remember { mutableStateOf(false) }
    val displayFocusRequester = remember { FocusRequester() }
    val editFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(autoFocus) {
        if (autoFocus) displayFocusRequester.requestFocusWhenReady()
    }

    // Entering edit mode focuses the real field, which is what raises the IME.
    //
    // Leaving it always puts focus back on the row first — the editor has just
    // been removed from the composition, so without this there is no focused
    // node at all and the remote goes dead. Only then is a pending directional
    // move applied. Doing it the other way round (moving focus and then
    // restoring) makes the restore undo the move, which reads as "Down does
    // nothing".
    LaunchedEffect(editing) {
        if (editing) {
            editFocusRequester.requestFocusWhenReady()
        } else if (everEdited) {
            displayFocusRequester.requestFocusWhenReady()
            pendingExitDirection?.let { direction ->
                focusManager.moveFocus(direction)
                pendingExitDirection = null
            }
        }
    }

    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        val shown = when {
            value.isEmpty() -> placeholder.orEmpty()
            isPassword -> "•".repeat(value.length.coerceAtMost(24))
            else -> value
        }

        if (!editing) {
            Surface(
                onClick = {
                    everEdited = true
                    editing = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(displayFocusRequester)
                    .onFocusChanged { focused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(ControlShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                        shape = ControlShape,
                    )
                ),
            ) {
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (value.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                )
            }
            return@Column
        }

        Box(
            Modifier
                .fillMaxWidth()
                .clip(ControlShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(3.dp, MaterialTheme.colorScheme.secondary, ControlShape)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { editing = false }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .focusRequester(editFocusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            // Vertical presses commit and continue through the
                            // form in one action rather than two. Note these
                            // only reach us once the IME has been dismissed —
                            // while it is up it is a separate window and owns
                            // every key, which is why Back is the documented
                            // way out of typing.
                            Key.DirectionDown -> {
                                pendingExitDirection = FocusDirection.Down
                                editing = false
                                true
                            }
                            Key.DirectionUp -> {
                                pendingExitDirection = FocusDirection.Up
                                editing = false
                                true
                            }
                            Key.Back -> {
                                editing = false
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
        }
    }
}

/**
 * A full-width settings row: label, optional summary, and a trailing value.
 * Everything in Settings is one of these so that focus movement down the
 * screen is uniform and predictable.
 */
@Composable
fun SettingRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(ControlShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                shape = ControlShape,
            )
        ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
    }
}

/** Capsule filter chip. Used for categories, seasons and guide day-jumps. */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                shape = RoundedCornerShape(50),
            )
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/** Row of chips with consistent spacing. */
val ChipSpacing = Arrangement.spacedBy(10.dp)

/** Fixed heights so lists and grids line up across screens. */
object Sizes {
    val channelRowHeight = 66.dp
    val posterWidth = 168.dp
    val posterHeight = 252.dp
    val sideNavWidth = 232.dp
    val sideNavCollapsedWidth = 84.dp
}

@Composable
fun VerticalSpace(height: Int) = Box(Modifier.height(height.dp))

@Composable
fun HorizontalSpace(width: Int) = Box(Modifier.width(width.dp))
