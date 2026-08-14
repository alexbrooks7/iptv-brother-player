package com.iptv.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.iptv.player.R
import com.iptv.player.ui.components.TvButton
import com.iptv.player.ui.components.TvTextField
import com.iptv.player.ui.theme.ErrorRed
import com.iptv.player.ui.theme.Scrim
import kotlinx.coroutines.delay

/**
 * Full-screen PIN entry.
 *
 * A screen rather than a dialog because tv-material has no dialog that behaves
 * well with a D-pad, and because a full-screen prompt makes it unambiguous
 * that the remote's next presses go here.
 *
 * The lockout after five wrong attempts is not really about brute force — a
 * four-digit PIN guarded by a parent is not a security boundary. It is about
 * the realistic failure mode, which is a child pressing digits at random until
 * something opens; a rising delay makes that unrewarding within a few tries.
 */
@Composable
fun PinPrompt(
    title: String,
    verify: (String) -> Boolean,
    /** Receives the accepted PIN, which the two-step "set a PIN" flow needs. */
    onAccepted: (String) -> Unit,
    onCancel: () -> Unit,
    rejectedMessage: String? = null,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableIntStateOf(0) }
    var lockedForSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(lockedForSeconds) {
        if (lockedForSeconds > 0) {
            delay(1_000)
            lockedForSeconds--
        }
    }

    val wrongPin = rejectedMessage ?: stringResource(R.string.pin_wrong)
    val lockedOut = stringResource(R.string.pin_locked_out, lockedForSeconds)

    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)

            TvTextField(
                value = pin,
                onValueChange = { value ->
                    // Digits only, four of them: the field is the whole
                    // interaction, so it enforces the format rather than
                    // validating after the fact.
                    if (value.length <= 4 && value.all(Char::isDigit)) {
                        pin = value
                        error = null
                    }
                },
                label = "",
                isPassword = true,
                keyboardType = KeyboardType.NumberPassword,
                autoFocus = true,
                modifier = Modifier.padding(top = 24.dp),
            )

            val visibleError = when {
                lockedForSeconds > 0 -> lockedOut
                else -> error
            }
            if (visibleError != null) {
                Text(
                    visibleError,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Row(
                Modifier.padding(top = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvButton(
                    text = stringResource(R.string.action_ok),
                    enabled = pin.length == 4 && lockedForSeconds == 0,
                    onClick = {
                        if (verify(pin)) {
                            val accepted = pin
                            pin = ""
                            error = null
                            attempts = 0
                            onAccepted(accepted)
                        } else {
                            attempts++
                            pin = ""
                            error = wrongPin
                            if (attempts >= 5) {
                                lockedForSeconds = 30
                                attempts = 0
                            }
                        }
                    },
                )
                TvButton(
                    text = stringResource(R.string.action_cancel),
                    primary = false,
                    onClick = onCancel,
                )
            }
        }
    }
}
