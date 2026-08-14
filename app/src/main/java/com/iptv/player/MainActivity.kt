package com.iptv.player

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.iptv.player.data.prefs.Settings
import com.iptv.player.di.ServiceLocator
import com.iptv.player.ui.MainScreen
import com.iptv.player.ui.theme.IptvTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Playback is the point of the app and a TV has no battery to protect,
        // so the screen should never time out under us mid-programme. The
        // system screensaver would otherwise replace live video after a few
        // minutes of no remote input, which is exactly what watching TV is.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Schedule the background refresh from the stored interval. Reading it
        // once here (rather than observing) is enough: changes go through
        // SettingsViewModel, which reschedules immediately.
        lifecycleScope.launch {
            val settings = ServiceLocator.settings.flow.first()
            ServiceLocator.refreshScheduler.ensureScheduled(settings.autoRefreshHours)
        }

        setContent {
            val settings by ServiceLocator.settings.flow
                .collectAsStateWithLifecycle(initialValue = Settings())

            IptvTheme(light = settings.lightTheme, uiScale = settings.uiScale) {
                MainScreen()
            }
        }
    }
}
