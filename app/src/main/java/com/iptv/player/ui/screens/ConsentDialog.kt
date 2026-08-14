package com.iptv.player.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.ui.util.requestFocusWhenReady
import com.iptv.player.ui.util.tvFocusGroup

/** Link blue, kept distinct from the app's green so URLs read as URLs. */
private val LinkBlue = Color(0xFF6EA8FE)

/**
 * Disclosure and opt-in for Pawns.app bandwidth sharing, drawn as a modal card
 * over whatever is behind it.
 *
 * **Why not the SDK's bundled consent Activity.** The SDK ships one, and custom
 * implementations are explicitly permitted. The stock screen is built for
 * phones and is close to unusable with a remote: every hyperlink paragraph is
 * its own focus stop, so the buttons are around a dozen D-pad presses away; the
 * buttons themselves draw no focus indicator, so there is no way to tell which
 * one is selected; and it is a full-page white scroll on a screen viewed from
 * across a room. This has exactly two focus stops, an unmistakable focus ring,
 * and fits without scrolling.
 *
 * **On the copy.** It says what Pawns.app actually receives — IP address and
 * approximate location — and what it costs, rather than the softer framing an
 * app owner might prefer. Pawns' own terms place responsibility for this
 * disclosure on the app owner, and both stores treat undisclosed traffic
 * routing as a policy violation, so vagueness here is a liability rather than a
 * kindness. Declining is a real, equally reachable choice, not a dark pattern:
 * one press away, same focus treatment, and it leaves the feature off.
 */
@Composable
fun ConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val acceptFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { acceptFocus.requestFocusWhenReady() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.widthIn(max = 940.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 48.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.consent_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.consent_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.consent_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp),
                )
                // Shown as plain text rather than clickable links on purpose:
                // there is no browser to hand off to on much of this hardware,
                // and a focusable link would add stops between the reader and
                // the two answers. The URLs are short enough to type.
                Text(
                    stringResource(R.string.consent_terms),
                    style = MaterialTheme.typography.labelMedium,
                    color = LinkBlue,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.consent_privacy),
                    style = MaterialTheme.typography.labelMedium,
                    color = LinkBlue,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Column(
                    Modifier
                        .padding(top = 28.dp)
                        .fillMaxWidth()
                        .tvFocusGroup(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        onClick = onAccept,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(acceptFocus),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(
                                BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                                shape = RoundedCornerShape(12.dp),
                            )
                        ),
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), Alignment.Center) {
                            Text(
                                stringResource(R.string.consent_accept),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }

                    Surface(
                        onClick = onDecline,
                        modifier = Modifier.fillMaxWidth(),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        border = ClickableSurfaceDefaults.border(
                            border = Border(
                                BorderStroke(1.dp, MaterialTheme.colorScheme.border),
                                shape = RoundedCornerShape(12.dp),
                            ),
                            focusedBorder = Border(
                                BorderStroke(3.dp, MaterialTheme.colorScheme.secondary),
                                shape = RoundedCornerShape(12.dp),
                            ),
                        ),
                    ) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), Alignment.Center) {
                            Text(
                                stringResource(R.string.consent_decline),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
