package com.renan.renanmusic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.exp
import kotlin.math.sin

/** Gera prints da UI real com faixas fictícias (capas desenhadas em código). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotsTest {
    @get:Rule val rule = createComposeRule()

    private fun cover(c1: Int, c2: Int, sun: Int): Bitmap {
        val b = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRect(0f, 0f, 600f, 600f, Paint().apply { shader = LinearGradient(0f, 0f, 600f, 600f, c1, c2, Shader.TileMode.CLAMP) })
        c.drawCircle(380f, 230f, 170f, Paint().apply { shader = RadialGradient(380f, 230f, 170f, sun, 0x00000000, Shader.TileMode.CLAMP) })
        c.drawCircle(380f, 230f, 90f, Paint().apply { color = sun })
        return b
    }

    private fun levels(energy: Float) = Levels(Spectrum.BANDS).apply {
        for (i in v.indices) v[i] = (energy * (0.35f + 0.65f * exp(-i / 7f)) * (0.7f + 0.3f * sin(i * 1.7f))).coerceIn(0f, 1f)
        bass = (v[0] + v[1] + v[2] + v[3]) / 4f
        phase = 1.3f
    }

    private fun shot(name: String, track: Track, pb: Playback, connected: Boolean = true, energy: Float = 0.9f, controls: Boolean = false) {
        // A posição anda a cada frame enquanto toca: o relógio do teste avança só à mão.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Ink)) {
                PlayerContent(track, pb, connected, levels(energy), false, {}, {}, {}, {}, {}, initialControls = controls)
                VersionTag(Modifier.align(Alignment.BottomEnd))
            }
        }
        Thread.sleep(400) // Palette roda em Dispatchers.Default
        rule.mainClock.advanceTimeBy(3000)
        rule.onRoot().captureRoboImage("../screenshots/$name.png")
    }

    private val now get() = android.os.SystemClock.elapsedRealtime()

    @Test fun sunset() = shot(
        "1-tocando-sunset",
        Track("Noites de Verão", "Marina Sena", cover(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFFFFAF7B.toInt()), 214_000),
        Playback(true, 81_000, now),
    )

    @Test fun ocean() = shot(
        "2-tocando-oceano",
        Track("Blue Hour (Extended Mix)", "Lane 8 · Arctic Lake", cover(0xFF0F2027.toInt(), 0xFF2C5364.toInt(), 0xFF7FE7D9.toInt()), 352_000),
        Playback(true, 210_000, now),
        energy = 0.6f,
    )

    @Test fun paused() = shot(
        "3-pausado",
        Track("Garota de Ipanema", "Tom Jobim", cover(0xFF1D2B13.toInt(), 0xFF6B8E23.toInt(), 0xFFF2E394.toInt()), 315_000),
        Playback(false, 40_000, now),
        energy = 0f,
    )

    @Test fun sunsetControls() = shot(
        "1b-tocando-controles",
        Track("Noites de Verão", "Marina Sena", cover(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFFFFAF7B.toInt()), 214_000),
        Playback(true, 81_000, now, like = LikeAction("spotify-like", liked = true)),
        controls = true,
    )

    @Test fun idle() = shot("4-nada-tocando", Track(), Playback(), connected = false)

    @Test fun onboarding() {
        rule.setContent {
            Box(Modifier.fillMaxSize().background(Ink)) {
                Onboarding(hasListener = false, onListener = {}, onAppInfo = {})
                VersionTag(Modifier.align(Alignment.BottomEnd))
            }
        }
        rule.onRoot().captureRoboImage("../screenshots/0-boas-vindas.png")
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-xxhdpi")
    fun landscape() = shot(
        "5-paisagem",
        Track("Noites de Verão", "Marina Sena", cover(0xFF3A1C71.toInt(), 0xFFD76D77.toInt(), 0xFFFFAF7B.toInt()), 214_000),
        Playback(true, 120_000, now),
    )
}
