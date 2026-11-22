package com.renan.renanmusic

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

class MainActivity : ComponentActivity() {
    private val spectrum = Spectrum()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Tela cheia imersiva: barras do sistema somem e só voltam, de passagem, com um deslize na borda.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val player = Player(applicationContext)
        setContent { Root(player, spectrum) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun Root(player: Player, spectrum: Spectrum) {
    val ctx = LocalContext.current
    var hasListener by remember { mutableStateOf(listenerEnabled(ctx)) }

    // Reconfere permissões ao voltar das Configurações.
    LifecycleResumeEffect(Unit) {
        hasListener = listenerEnabled(ctx)
        onPauseOrDispose { }
    }
    // Só escuta mídia e áudio com o app em primeiro plano: zero custo em segundo plano.
    LifecycleResumeEffect(hasListener) {
        if (hasListener) player.start()
        onPauseOrDispose { player.stop(); spectrum.stop() }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (hasListener) {
            PlayerScreen(player, spectrum)
        } else {
            Onboarding(
                hasListener = hasListener,
                onListener = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onAppInfo = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                },
            )
        }
        VersionTag(Modifier.align(Alignment.BottomEnd))
    }
}

private fun listenerEnabled(ctx: Context) =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

