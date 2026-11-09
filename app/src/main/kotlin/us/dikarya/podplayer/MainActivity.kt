/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package us.dikarya.podplayer

import android.app.UiModeManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import us.dikarya.podplayer.core.data.settings.ThemeMode
import us.dikarya.podplayer.settings.applicationNightMode
import us.dikarya.podplayer.settings.isDark
import us.dikarya.podplayer.ui.shell.AppShell
import us.dikarya.podplayer.ui.theme.DarkTokens
import us.dikarya.podplayer.ui.theme.LightTokens
import us.dikarya.podplayer.ui.theme.PodplayerColors
import us.dikarya.podplayer.ui.theme.PodplayerTheme

class MainActivity : ComponentActivity() {
    @Volatile
    private var themeWaitOver = false
    private var appliedDark: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = appGraph

        setContent {
            val saved by graph.themeMode.collectAsStateWithLifecycle()
            val mode = saved ?: ThemeMode.DEFAULT
            val colors = if (mode.isDark(isSystemInDarkTheme())) DarkTokens else LightTokens
            ApplyWindowColors(colors)
            PodplayerTheme(colors) {
                AppShell(
                    themeMode = mode,
                    onThemeModeChange = { lifecycleScope.launch { graph.settings.setThemeMode(it) } },
                )
            }
        }

        // No frame is drawn until the saved theme is known, so there is no flash of the wrong
        // theme. The read started at process start; if it takes over a second, draw the default.
        holdFirstFrameUntil { graph.themeMode.value != null || themeWaitOver }
        lifecycleScope.launch {
            delay(THEME_WAIT_MS)
            themeWaitOver = true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.themeMode.filterNotNull().collect(::rememberNightModeForStartingWindow)
            }
        }
    }

    private fun holdFirstFrameUntil(ready: () -> Boolean) {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!ready()) return false
                    content.viewTreeObserver.removeOnPreDrawListener(this)
                    return true
                }
            },
        )
    }

    /** Android 12+ keeps this per app, so the system starting window matches the saved theme next time. */
    private fun rememberNightModeForStartingWindow(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(UiModeManager::class.java).setApplicationNightMode(mode.applicationNightMode())
        }
    }

    @Composable
    private fun ApplyWindowColors(colors: PodplayerColors) {
        SideEffect {
            if (appliedDark == colors.isDark) return@SideEffect
            appliedDark = colors.isDark
            val transparent = android.graphics.Color.TRANSPARENT
            val style = if (colors.isDark) {
                SystemBarStyle.dark(transparent)
            } else {
                SystemBarStyle.light(transparent, transparent)
            }
            enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            window.setBackgroundDrawable(colors.background.toArgb().toDrawable())
        }
    }

    private companion object {
        const val THEME_WAIT_MS = 1_000L
    }
}
