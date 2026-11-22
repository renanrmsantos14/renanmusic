package com.renan.renanmusic

import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

// RenanMusic · Direção 01: capa grande, fundo = a própria capa extremamente desfocada, tipografia pesada sem serifa.
val Ink = Color(0xFF0E0D0B)
private val Paper = Color(0xFFF6EFE9)
private val Muted = Paper.copy(alpha = 0.6f)
private val Mono = FontFamily.Monospace
private val Sans = FontFamily.SansSerif
private val ArtShape = RoundedCornerShape(6.dp)

private val EaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private val EaseSheet = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private const val HIDE_AFTER_MS = 4000L

/** Identidade da música (não da versão do metadado): mesma faixa com capa nova mantém a camada. */
private val Track.songKey get() = "$artist|$title"

// ---------- Player ----------

@Composable
fun PlayerScreen(player: Player, spectrum: Spectrum) {
    val track by player.track.collectAsStateWithLifecycle()
    val pb by player.playback.collectAsStateWithLifecycle()
    val connected by player.connected.collectAsStateWithLifecycle()
    val queue by player.queue.collectAsStateWithLifecycle()
    val volume by player.volume.collectAsStateWithLifecycle()
    val levels = rememberLevels(spectrum, pb.playing)
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("renanmusic", android.content.Context.MODE_PRIVATE) }
    var lyricsOn by remember { mutableStateOf(prefs.getBoolean("lyrics", false)) }
    val view = LocalView.current
    LaunchedEffect(pb.playing) { view.keepScreenOn = pb.playing }
    PlayerContent(
        track, pb, connected, levels, rememberReducedMotion(),
        onOpen = player::openSpotify, onSeek = player::seek,
        onPrev = player::previous, onToggle = player::toggle, onNext = player::next,
        queue = queue, onSkipTo = player::skipTo, volume = volume, onVolume = player::setVolume,
        lyricsOn = lyricsOn, onLyricsToggle = { lyricsOn = !lyricsOn; prefs.edit().putBoolean("lyrics", lyricsOn).apply() },
        onLike = player::toggleLike,
        onResume = { if (!player.resume()) player.openSpotify() },
    )
}

private enum class Axis { None, Horizontal, Vertical }

/** UI pura do player (sem serviços do sistema): usada no app e nos prints de exemplo. */
@Composable
fun PlayerContent(
    track: Track,
    pb: Playback,
    connected: Boolean,
    levels: Levels,
    reduced: Boolean,
    onOpen: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    initialControls: Boolean = false,
    queue: List<QueueEntry> = emptyList(),
    onSkipTo: (Long) -> Unit = {},
    volume: Float = 0.5f,
    onVolume: (Float) -> Unit = {},
    lyricsOn: Boolean = false,
    onLyricsToggle: () -> Unit = {},
    onLike: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    var controls by remember { mutableStateOf(initialControls) }
    var queueOpen by remember { mutableStateOf(false) }
    var touch by remember { mutableIntStateOf(0) } // cada interação reinicia o timer de ocultar
    val poke: () -> Unit = { touch++ }
    LaunchedEffect(controls, touch) {
        if (controls && !initialControls) { delay(HIDE_AFTER_MS); controls = false }
    }
    val framePos = rememberPosition(pb)
    val pos by remember { derivedStateOf { framePos.value / 250 * 250 } }
    val lyrics by produceState(emptyList<LyricLine>(), track.title, track.artist, lyricsOn) {
        value = emptyList()
        if (lyricsOn) value = Lyrics.fetch(track.title, track.artist, track.duration)
    }
    val view = LocalView.current
    val density = LocalDensity.current

    // Carrossel de duas camadas: a faixa atual (shown) sai e a próxima (incoming) entra no MESMO movimento.
    // dragX é a posição da camada atual; a camada que chega fica sempre a um "travel" de distância, no lado oposto.
    val dragX = remember { Animatable(0f) }
    var shown by remember { mutableStateOf(track) }
    var incoming by remember { mutableStateOf<Track?>(null) }
    var pending by remember { mutableIntStateOf(0) } // 1 = próxima, -1 = anterior, 0 = nenhuma troca em curso
    val travel = with(density) { (LocalConfiguration.current.screenWidthDp.dp * 0.5f).toPx() }
    var volumeShown by remember { mutableStateOf(false) }
    var volumeTouch by remember { mutableIntStateOf(0) }
    LaunchedEffect(volumeTouch) { if (volumeTouch > 0) { volumeShown = true; delay(900); volumeShown = false } }
    val scope = rememberCoroutineScope()

    // Toque duplo: selo de play/pausa no centro + a capa afunda de leve enquanto o dedo está na tela.
    var flash by remember { mutableIntStateOf(0) }
    val flashT = remember { Animatable(0f) }
    var pressed by remember { mutableStateOf(false) }
    val pressScale = animateFloatAsState(if (pressed) 0.985f else 1f, spring(dampingRatio = 0.9f, stiffness = 900f), label = "press")
    LaunchedEffect(flash) {
        if (flash == 0) return@LaunchedEffect
        flashT.snapTo(0f)
        flashT.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 520f))
        delay(360)
        flashT.animateTo(0f, tween(240, easing = EaseOut))
    }

    // Troca de camada e volta ao centro no mesmo frame: nunca aparece um quadro vazio entre as faixas.
    suspend fun commit() { dragX.snapTo(0f); incoming?.let { shown = it }; incoming = null; pending = 0 }
    fun skip(dir: Int, velocity: Float = 0f) {
        // "Anterior" depois de 3 s só reinicia a faixa: a capa recua e volta ao centro, sem sair da tela.
        val restart = dir < 0 && pos > 3000
        if (dir > 0) onNext() else onPrev()
        if (reduced || pending != 0) return
        if (restart) {
            scope.launch {
                if (velocity == 0f) dragX.animateTo(travel * 0.1f, spring(dampingRatio = 1f, stiffness = 900f))
                dragX.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 320f), initialVelocity = velocity)
            }
            return
        }
        pending = dir
        scope.launch {
            // Um único spring até o fim do percurso: a camada atual sai e a nova entra junto, sem parada no meio.
            dragX.animateTo(-dir * travel, spring(dampingRatio = 1f, stiffness = 170f), initialVelocity = velocity)
            if (incoming != null) commit()
        }
    }
    LaunchedEffect(track) {
        when {
            pending != 0 -> {
                incoming = track
                if (!dragX.isRunning) commit() // os metadados chegaram depois do movimento: só troca a camada
            }
            // Mesma música (capa chegou depois, posição mudou) ou movimento reduzido: troca direta.
            reduced || shown.title.isEmpty() || shown.songKey == track.songKey -> shown = track
            else -> {
                // Troca feita pelo próprio Spotify (fim da faixa, outro aparelho): entra pelo mesmo carrossel.
                // Roda no escopo da tela para um novo metadado da mesma faixa não cortar o movimento no meio.
                incoming = track; pending = 1
                scope.launch {
                    dragX.animateTo(-travel, spring(dampingRatio = 1f, stiffness = 200f))
                    if (incoming != null) commit()
                }
            }
        }
    }
    LaunchedEffect(pending) { // player não respondeu: volta ao centro sem travar a tela
        if (pending == 0) return@LaunchedEffect
        delay(1800)
        if (incoming == null) { pending = 0; dragX.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 240f)) }
    }

    val revealState = animateFloatAsState(
        if (controls) 1f else 0f,
        spring(dampingRatio = 0.9f, stiffness = 520f),
        label = "reveal",
    )

    BackHandler(queueOpen) { queueOpen = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .pointerInput(connected) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { controls = !controls },
                    onLongPress = {
                        if (connected) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onOpen() // abre a faixa atual no Spotify
                        }
                    },
                    onDoubleTap = {
                        if (connected) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            onToggle()
                            flash++
                        }
                    },
                )
            }
            .pointerInput(connected) {
                if (!connected) return@pointerInput
                val slop = 18.dp.toPx()
                val commit = 72.dp.toPx()
                var axis = Axis.None
                var edge = false
                val edgeV = 56.dp.toPx()
                val edgeH = 28.dp.toPx()
                var dx = 0f; var dy = 0f
                var startVolume = 0f
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = { o ->
                        axis = Axis.None; dx = 0f; dy = 0f; startVolume = volume; tracker.resetTracking()
                        // Faixas de borda ficam para o sistema: puxar notificações, gesto de voltar, barra inferior.
                        edge = o.y < edgeV || o.y > size.height - edgeV || o.x < edgeH || o.x > size.width - edgeH
                    },
                    onDragEnd = {
                        if (!edge && axis == Axis.Horizontal) {
                            val vx = tracker.calculateVelocity().x
                            val flick = abs(vx) > 900f && vx * dx > 0 // gesto rápido no mesmo sentido basta
                            if (abs(dx) > commit || flick) {
                                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                                skip(if (dx < 0) 1 else -1, vx * 0.45f)
                            } else {
                                scope.launch { dragX.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 380f), initialVelocity = vx * 0.45f) }
                            }
                        }
                        axis = Axis.None
                    },
                    onDragCancel = { axis = Axis.None; scope.launch { dragX.animateTo(0f) } },
                ) { change, drag ->
                    if (edge) return@detectDragGestures
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    dx += drag.x; dy += drag.y
                    if (axis == Axis.None && (abs(dx) > slop || abs(dy) > slop)) {
                        axis = if (abs(dx) > abs(dy)) Axis.Horizontal else Axis.Vertical
                    }
                    when (axis) {
                        Axis.Horizontal -> scope.launch { dragX.snapTo(dx * 0.45f) }

                        Axis.Vertical -> {
                            val v = (startVolume - (dy - slop * kotlin.math.sign(dy)) / (size.height * 0.7f)).coerceIn(0f, 1f)
                            if (abs(v - volume) >= 0.02f) onVolume(v)
                            volumeTouch++
                        }
                        Axis.None -> Unit
                    }
                }
            }
    ) {
        Backdrop(track.art)
        if (!connected) {
            Idle(onResume, onOpen)
            return@Box
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = maxWidth > maxHeight
            val pad = if (landscape) 40.dp else 32.dp
            // Título com tamanho fixo e escala na camada de desenho: nenhuma remedição de texto por frame.
            val titleSize = if (landscape) 58f else 52f
            val titleMin = if (landscape) 44f / 58f else 40f / 52f
            val reveal = { revealState.value }
            // Camada 0 = faixa atual, camada 1 = faixa que chega (um travel adiante, do lado para onde o dedo foi).
            fun Modifier.slide(depth: Float, layer: Int) = graphicsLayer {
                val x = dragX.value + layer * pending * travel
                val p = (abs(x) / travel).coerceIn(0f, 1f)
                translationX = x * depth
                alpha = 1f - p * p
                val sc = 1f - 0.06f * p
                scaleX = sc; scaleY = sc
            }
            val art: @Composable (Modifier) -> Unit = { m ->
                Box(m.aspectRatio(1f).graphicsLayer { val s = lerp(1f, 0.96f, revealState.value) * pressScale.value; scaleX = s; scaleY = s }) {
                    // key por faixa: a capa só faz crossfade quando chega atrasada para a MESMA música.
                    key(shown.songKey) { Art(shown.art, Modifier.slide(1f, 0)) }
                    incoming?.let { key(it.songKey) { Art(it.art, Modifier.slide(1f, 1)) } }
                }
            }
            val titles: @Composable () -> Unit = {
                Box(
                    Modifier.fillMaxWidth().graphicsLayer {
                        val s = lerp(1f, titleMin, revealState.value)
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(0f, 1f)
                    }
                ) {
                    Titles(shown, titleSize, Modifier.slide(1.25f, 0))
                    incoming?.let { Titles(it, titleSize, Modifier.slide(1.25f, 1)) }
                }
            }

            val header: @Composable () -> Unit = { Header(pb.playing, pos, track.duration) }
            val body: @Composable () -> Unit = {
                LyricLine(lyrics, pos + 250, lyricsOn, Modifier.slide(1.4f, 0))
                titles()
                AnimatedVisibility(
                    visible = controls,
                    enter = expandVertically(tween(320, easing = EaseOut)) + fadeIn(tween(200, easing = EaseOut)),
                    exit = shrinkVertically(tween(260, easing = EaseOut)) + fadeOut(tween(140, easing = EaseOut)),
                ) {
                    Column {
                        Spacer(Modifier.height(26.dp))
                        Box(Modifier.stagger(reveal, 0f)) {
                            Progress({ framePos.value }, track.duration, onSeek = { poke(); onSeek(it) }, onDrag = poke)
                        }
                        Spacer(Modifier.height(6.dp))
                        Controls(
                            pb.playing, landscape, reveal,
                            onPrev = { poke(); skip(-1) }, onToggle = { poke(); onToggle() }, onNext = { poke(); skip(1) },
                            onQueue = { poke(); queueOpen = true },
                            lyricsOn = lyricsOn, onLyrics = { poke(); onLyricsToggle() },
                            like = pb.like, onLike = { poke(); onLike() },
                        )
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(pad)) {
                    art(Modifier.fillMaxHeight())
                    Spacer(Modifier.width(48.dp))
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                        header()
                        Column { body() }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = pad, vertical = 24.dp)) {
                    header()
                    Spacer(Modifier.height(28.dp))
                    art(Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                    body()
                    Spacer(Modifier.height(8.dp))
                }
            }
            VolumeIndicator(volume, volumeShown, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
        }
        // Com controles ocultos, o progresso vira um fio na borda inferior da tela.
        Thread({ framePos.value }, track.duration, { 1f - revealState.value }, Modifier.align(Alignment.BottomStart))
        Box(
            Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    val t = flashT.value
                    alpha = t.coerceIn(0f, 1f)
                    val sc = 0.72f + 0.28f * t
                    scaleX = sc; scaleY = sc
                }
                .size(92.dp)
                .background(Ink.copy(alpha = 0.35f), CircleShape)
                .border(1.dp, Paper.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(30.dp)) { if (pb.playing) playIcon(Paper) else pauseIcon(Paper) }
        }
        QueueSheet(queue, queueOpen, onClose = { queueOpen = false }, onPick = { onSkipTo(it); queueOpen = false })
    }
}

/** Entrada escalonada: cada elemento sobe e aparece um pouco depois do anterior. */
private fun Modifier.stagger(reveal: () -> Float, delay: Float) = graphicsLayer {
    val r = ((reveal() - delay) / (1f - delay)).coerceIn(0f, 1f)
    alpha = r
    translationY = (1f - r) * 14.dp.toPx()
}

/** A capa inteira, muito desfocada e um pouco escurecida: o fundo tem exatamente as cores do álbum.
 *  Trabalha com uma miniatura de 40 px: o desfoque custa quase nada e a troca de cor é imediata. */
@Composable
private fun Backdrop(art: Bitmap?) {
    val tiny = remember(art) { art?.let { Bitmap.createScaledBitmap(it, 40, 40, true).asImageBitmap() } }
    Crossfade(tiny, animationSpec = tween(600, easing = EaseOut), label = "backdrop") { img ->
        if (img == null) return@Crossfade
        Box(Modifier.fillMaxSize()) {
            Image(
                img, null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .blur(48.dp),
            )
            Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.42f)))
        }
    }
}

@Composable
private fun Header(playing: Boolean, pos: Long, duration: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Meta(if (playing) "TOCANDO" else "PAUSADO")
        Meta("${clock(pos)} / ${clock(duration)}")
    }
}

@Composable
private fun Meta(text: String, modifier: Modifier = Modifier) = BasicText(
    text,
    modifier = modifier,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    style = TextStyle(Paper.copy(alpha = 0.55f), 11.sp, fontFamily = Mono, letterSpacing = 1.sp),
)

@Composable
private fun Idle(onResume: () -> Unit, onOpen: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(40.dp), verticalArrangement = Arrangement.Bottom) {
        Meta("SILÊNCIO")
        Spacer(Modifier.height(12.dp))
        BasicText("Nada tocando.", style = TextStyle(Paper, 48.sp, FontWeight.Bold, fontFamily = Sans, letterSpacing = (-2).sp, lineHeight = 48.sp))
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button("Retomar no Spotify", onResume)
            Meta("ABRIR", Modifier.clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 14.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Titles(track: Track, size: Float, modifier: Modifier) {
    val title = track.title
    val artist = track.artist
    Column(modifier.fillMaxWidth()) {
            BasicText(
                title.ifEmpty { " " },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(Paper, size.sp, FontWeight.Bold, fontFamily = Sans, letterSpacing = (-0.045 * size).sp, lineHeight = (size * 0.96f).sp),
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                artist,
                maxLines = 1,
                modifier = Modifier.basicMarquee(initialDelayMillis = 3000),
                style = TextStyle(Muted, 17.sp, FontWeight.Medium, fontFamily = Sans),
            )
    }
}

@Composable
private fun Art(art: Bitmap?, modifier: Modifier) {
    Box(modifier.fillMaxSize().clip(ArtShape).background(Paper.copy(alpha = 0.08f))) {
        Crossfade(art, animationSpec = tween(280, easing = EaseOut), label = "art") { a ->
            if (a != null) {
                val img = remember(a) { a.asImageBitmap() }
                Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Uma linha da letra por vez, acima do título; troca com subida curta. Some quando não há letra sincronizada. */
@Composable
private fun LyricLine(lines: List<LyricLine>, pos: Long, on: Boolean, modifier: Modifier) {
    val current = if (!on || lines.isEmpty()) null else lines.lastOrNull { it.at <= pos }?.text?.takeIf { it.isNotBlank() }
    AnimatedVisibility(current != null, modifier, enter = fadeIn(tween(300, easing = EaseOut)), exit = fadeOut(tween(200, easing = EaseOut))) {
        AnimatedContent(
            current.orEmpty(),
            transitionSpec = {
                (fadeIn(tween(320, easing = EaseOut)) + slideInVertically(tween(360, easing = EaseOut)) { it / 2 })
                    .togetherWith(fadeOut(tween(160, easing = EaseOut)) + slideOutVertically(tween(220, easing = EaseOut)) { -it / 3 })
            },
            label = "lyric",
        ) { text ->
            BasicText(
                text,
                Modifier.fillMaxWidth().padding(bottom = 18.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(Paper.copy(alpha = 0.78f), 19.sp, FontWeight.Medium, fontFamily = Sans, letterSpacing = (-0.2).sp, lineHeight = 24.sp),
            )
        }
    }
}

/** Indicador de volume: fio vertical na borda direita, só durante o gesto. */
@Composable
private fun VolumeIndicator(volume: Float, shown: Boolean, modifier: Modifier) {
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 120 else 360, easing = EaseOut), label = "vol")
    val v by animateFloatAsState(volume, spring(dampingRatio = 0.9f, stiffness = 700f), label = "volv")
    Column(modifier.graphicsLayer { this.alpha = alpha }, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.width(3.dp).fillMaxHeight(0.42f)) {
            drawRect(Paper.copy(alpha = 0.18f))
            val h = size.height * v
            drawRect(Paper, Offset(0f, size.height - h), Size(size.width, h))
        }
        Spacer(Modifier.height(10.dp))
        Meta("${(v * 100).toInt()}")
    }
}

/** Fila da sessão: painel que entra pela borda, fecha com toque fora, Voltar ou arrasto. */
@Composable
private fun QueueSheet(queue: List<QueueEntry>, open: Boolean, onClose: () -> Unit, onPick: (Long) -> Unit) {
    AnimatedVisibility(open, enter = fadeIn(tween(200, easing = EaseOut)), exit = fadeOut(tween(200, easing = EaseOut))) {
        Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { onClose() } })
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        AnimatedVisibility(
            open,
            modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (landscape) slideInHorizontally(tween(340, easing = EaseSheet)) { it } else slideInVertically(tween(340, easing = EaseSheet)) { it },
            exit = if (landscape) slideOutHorizontally(tween(260, easing = EaseSheet)) { it } else slideOutVertically(tween(260, easing = EaseSheet)) { it },
        ) {
            Column(
                Modifier
                    .then(if (landscape) Modifier.fillMaxHeight().width(360.dp) else Modifier.fillMaxWidth().fillMaxHeight(0.62f))
                    .background(Ink.copy(alpha = 0.92f))
                    .pointerInput(Unit) { detectTapGestures { } } // não deixa o toque vazar para o scrim
                    .safeDrawingPadding()
                    .padding(horizontal = 28.dp, vertical = 24.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Meta("A SEGUIR")
                    Meta("FECHAR", Modifier.clickable(onClick = onClose))
                }
                Spacer(Modifier.height(18.dp))
                if (queue.isEmpty()) {
                    BasicText(
                        "O Spotify não compartilhou a fila.",
                        style = TextStyle(Muted, 15.sp, fontFamily = Sans, lineHeight = 22.sp),
                    )
                } else {
                    LazyColumn {
                        itemsIndexed(queue, key = { _, e -> e.id }) { i, e ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(e.id) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BasicText("%02d".format(i + 1), Modifier.width(40.dp), style = TextStyle(Muted, 11.sp, fontFamily = Mono))
                                Column(Modifier.weight(1f)) {
                                    BasicText(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(Paper, 16.sp, FontWeight.Medium, fontFamily = Sans))
                                    if (e.subtitle.isNotEmpty()) {
                                        BasicText(e.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(Muted, 13.sp, fontFamily = Sans))
                                    }
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Paper.copy(alpha = 0.08f)))
                        }
                    }
                }
            }
        }
    }
}

// ---------- Níveis de áudio ----------

/** Níveis suavizados por frame. Lidos só na fase de desenho: nada recompõe a 60/120 fps. */
class Levels(n: Int) {
    val v = FloatArray(n)
    var bass = 0f
    var phase = 0f
    val tick = mutableLongStateOf(0L)
}

@Composable
private fun rememberLevels(spectrum: Spectrum, playing: Boolean): Levels {
    val levels = remember { Levels(Spectrum.BANDS) }
    val isPlaying by rememberUpdatedState(playing)
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { t ->
                val src = spectrum.bands
                for (i in levels.v.indices) {
                    val target = if (isPlaying) src[i] else 0f
                    val d = levels.v[i]
                    levels.v[i] = d + (target - d) * (if (target > d) 0.45f else 0.10f)
                }
                levels.bass = (levels.v[0] + levels.v[1] + levels.v[2] + levels.v[3]) / 4f
                levels.tick.longValue = t
            }
            // Parado ou sem captura de áudio: nada a animar, poupa bateria.
            if (levels.bass < 0.002f && (!isPlaying || spectrum.bands.all { it == 0f })) delay(120)
        }
    }
    return levels
}

// ---------- Progresso ----------

/** Posição estimada a cada frame. Lida só no desenho (barra e fio), então anda liso sem recompor a tela. */
@Composable
private fun rememberPosition(pb: Playback): State<Long> {
    val pos = remember { mutableLongStateOf(pb.position) }
    LaunchedEffect(pb) {
        fun estimate() = if (pb.playing) pb.position + ((SystemClock.elapsedRealtime() - pb.updatedAt) * pb.speed).toLong() else pb.position
        pos.longValue = estimate()
        while (pb.playing) withFrameMillis { pos.longValue = estimate() }
    }
    return pos
}

/** Fio de 3dp colado na borda inferior: só aparece com os controles ocultos. */
@Composable
private fun Thread(pos: () -> Long, duration: Long, alpha: () -> Float, modifier: Modifier) {
    Canvas(modifier.fillMaxWidth().height(3.dp).graphicsLayer { this.alpha = alpha() }) {
        val frac = if (duration > 0) (pos().toFloat() / duration).coerceIn(0f, 1f) else 0f
        drawRect(Paper.copy(alpha = 0.14f))
        drawRect(Paper, size = Size(size.width * frac, size.height))
    }
}

@Composable
private fun Progress(pos: () -> Long, duration: Long, onSeek: (Long) -> Unit, onDrag: () -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val view = LocalView.current
    // Marcador cresce com spring ao segurar, em vez de pular de 16 para 20 dp.
    val grab by animateFloatAsState(if (drag != null) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 700f), label = "grab")
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(duration) { detectTapGestures { onSeek((it.x / size.width * duration).toLong()) } }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { onDrag(); drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        drag?.let { onSeek((it * duration).toLong()) }
                        view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        drag = null
                    },
                    onDragCancel = { drag = null },
                ) { change, _ -> onDrag(); drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
    ) {
        val frac = drag ?: if (duration > 0) (pos().toFloat() / duration).coerceIn(0f, 1f) else 0f
        val y = size.height / 2
        val h = 2.dp.toPx()
        drawRect(Paper.copy(alpha = 0.18f), Offset(0f, y - h / 2), Size(size.width, h))
        drawRect(Paper, Offset(0f, y - h / 2), Size(size.width * frac, h))
        val mw = 2.dp.toPx()
        val mh = (16 + 4 * grab).dp.toPx()
        drawRect(Paper, Offset(size.width * frac - mw / 2, y - mh / 2), Size(mw, mh))
    }
}

private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------- Controles ----------

@Composable
private fun Controls(
    playing: Boolean, landscape: Boolean, reveal: () -> Float,
    onPrev: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit,
    lyricsOn: Boolean, onLyrics: () -> Unit,
    like: LikeAction? = null, onLike: () -> Unit = {},
) {
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().offset(x = (-18).dp),
        horizontalArrangement = if (landscape) Arrangement.spacedBy(8.dp) else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Press(onPrev, Modifier.stagger(reveal, 0.15f)) { Canvas(Modifier.size(20.dp)) { skipIcon(Paper, flip = true) } }
        Press({ view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onToggle() }, Modifier.stagger(reveal, 0.25f)) {
            Canvas(Modifier.size(22.dp)) { if (playing) pauseIcon(Paper) else playIcon(Paper) }
        }
        Press(onNext, Modifier.stagger(reveal, 0.35f)) { Canvas(Modifier.size(20.dp)) { skipIcon(Paper, flip = false) } }
        if (landscape) Spacer(Modifier.weight(1f))
        if (like != null) {
            // Curtir no Spotify: o coração enche com um pulso curto (sobe e volta ao tamanho normal).
            val pop by animateFloatAsState(if (like.liked) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "like")
            Press({ view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onLike() }, Modifier.stagger(reveal, 0.4f), size = 48.dp) {
                Canvas(Modifier.size(20.dp).graphicsLayer { val s = 1f + 0.72f * pop * (1f - pop); scaleX = s; scaleY = s }) {
                    heartIcon(Paper, filled = like.liked)
                }
            }
        }
        BasicText(
            "LETRA",
            Modifier
                .stagger(reveal, 0.45f)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onLyrics)
                .padding(horizontal = if (like != null) 8.dp else 12.dp, vertical = 20.dp),
            style = TextStyle(if (lyricsOn) Paper else Paper.copy(alpha = 0.45f), 11.sp, fontFamily = Mono, letterSpacing = 1.sp),
        )
        Meta(
            "FILA",
            Modifier
                .stagger(reveal, 0.55f)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onQueue)
                .padding(horizontal = if (like != null) 8.dp else 18.dp, vertical = 20.dp),
        )
    }
}

/** Feedback no toque (press-in), ação no soltar. Escala sutil, sem ripple. Alvo de 56dp. */
@Composable
private fun Press(onClick: () -> Unit, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 56.dp, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(120, easing = EaseOut), label = "press")
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) 0.6f else 1f }
            .clickable(source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private fun DrawScope.playIcon(c: Color) {
    val w = size.width; val h = size.height
    drawPath(Path().apply { moveTo(w * 0.2f, h * 0.1f); lineTo(w * 0.9f, h * 0.5f); lineTo(w * 0.2f, h * 0.9f); close() }, c)
}

private fun DrawScope.heartIcon(c: Color, filled: Boolean) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * 0.5f, h * 0.88f)
        cubicTo(w * 0.1f, h * 0.6f, w * 0.0f, h * 0.36f, w * 0.12f, h * 0.2f)
        cubicTo(w * 0.26f, h * 0.02f, w * 0.46f, h * 0.1f, w * 0.5f, h * 0.28f)
        cubicTo(w * 0.54f, h * 0.1f, w * 0.74f, h * 0.02f, w * 0.88f, h * 0.2f)
        cubicTo(w * 1.0f, h * 0.36f, w * 0.9f, h * 0.6f, w * 0.5f, h * 0.88f)
        close()
    }
    if (filled) drawPath(p, c) else drawPath(p, c, style = Stroke(width = 1.6.dp.toPx()))
}

private fun DrawScope.pauseIcon(c: Color) {
    val w = size.width; val h = size.height; val bw = w * 0.2f
    drawRect(c, Offset(w * 0.2f, h * 0.12f), Size(bw, h * 0.76f))
    drawRect(c, Offset(w * 0.6f, h * 0.12f), Size(bw, h * 0.76f))
}

private fun DrawScope.skipIcon(c: Color, flip: Boolean) {
    val w = size.width; val h = size.height
    fun x(f: Float) = if (flip) w * (1 - f) else w * f
    drawPath(Path().apply { moveTo(x(0.12f), h * 0.14f); lineTo(x(0.72f), h * 0.5f); lineTo(x(0.12f), h * 0.86f); close() }, c)
    drawRect(c, Offset(minOf(x(0.8f), x(0.9f)), h * 0.14f), Size(w * 0.1f, h * 0.72f))
}

// ---------- Onboarding ----------

@Composable
fun Onboarding(
    hasListener: Boolean,
    onListener: () -> Unit,
    onAppInfo: () -> Unit,
) {
    val hair = Paper.copy(alpha = 0.12f)
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 28.dp), verticalArrangement = Arrangement.Bottom) {
        Meta("VISUALIZADOR")
        Spacer(Modifier.height(10.dp))
        BasicText("RenanMusic", style = TextStyle(Paper, 52.sp, FontWeight.Bold, fontFamily = Sans, letterSpacing = (-2.2).sp, lineHeight = 54.sp))
        Spacer(Modifier.height(12.dp))
        BasicText("A capa do que está tocando, em tela cheia.", style = TextStyle(Muted, 16.sp, fontFamily = Sans, lineHeight = 24.sp))
        Spacer(Modifier.height(48.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
        Step("01", "Acesso à mídia", "Lê a música e controla o player. Nenhuma notificação é lida.", hasListener, onListener)
        if (!hasListener) {
            BasicText(
                "Bloqueado? Info do app → ⋮ → Permitir configurações restritas.",
                modifier = Modifier.padding(start = 44.dp, bottom = 20.dp).clickable(onClick = onAppInfo),
                style = TextStyle(Muted, 12.sp, fontFamily = Sans, lineHeight = 18.sp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
    }
}

@Composable
private fun Step(n: String, title: String, body: String, done: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.Top) {
        BasicText(n, modifier = Modifier.width(44.dp), style = TextStyle(Muted, 12.sp, fontFamily = Mono, lineHeight = 22.sp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(if (done) Muted else Paper, 17.sp, FontWeight.Medium, fontFamily = Sans, lineHeight = 22.sp))
            Spacer(Modifier.height(4.dp))
            BasicText(body, style = TextStyle(Muted, 13.sp, fontFamily = Sans, lineHeight = 19.sp))
            if (!done) {
                Spacer(Modifier.height(16.dp))
                Button("Permitir", onClick)
            }
        }
        if (done) BasicText("ok", style = TextStyle(Muted, 12.sp, fontFamily = Mono, letterSpacing = 1.sp, lineHeight = 22.sp))
    }
}

@Composable
private fun Button(text: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, tween(120, easing = EaseOut), label = "button")
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(Paper, RoundedCornerShape(6.dp))
            .clickable(source, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) { BasicText(text, style = TextStyle(Ink, 14.sp, FontWeight.Medium, fontFamily = Sans)) }
}

// ---------- Utilidades ----------

/** Aviso discreto no topo quando há versão nova baixada; entra descendo e some ao instalar. */
@Composable
fun UpdatePill(update: Update, onInstall: () -> Unit, modifier: Modifier) {
    val ready = update as? Update.Ready
    var last by remember { mutableStateOf(ready) }
    if (ready != null) last = ready
    AnimatedVisibility(
        update !is Update.None,
        modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 18.dp, vertical = 10.dp),
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { -it * 2 } + fadeIn(tween(200, easing = EaseOut)),
        exit = slideOutVertically(tween(220, easing = EaseOut)) { -it * 2 } + fadeOut(tween(160, easing = EaseOut)),
    ) {
        val installing = update is Update.Installing
        Row(
            // Ocupa a faixa do cabeçalho inteira: cobre "TOCANDO / tempo" em vez de ficar por cima pela metade.
            Modifier
                .fillMaxWidth()
                .background(Ink, RoundedCornerShape(50))
                .border(1.dp, Paper.copy(alpha = 0.14f), RoundedCornerShape(50))
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !installing, onClick = onInstall)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Meta("v${last?.version.orEmpty()} DISPONÍVEL")
            BasicText(
                if (installing) "INSTALANDO…" else "ATUALIZAR",
                style = TextStyle(Paper, 11.sp, FontWeight.Bold, fontFamily = Mono, letterSpacing = 1.sp),
            )
        }
    }
}

@Composable
fun VersionTag(modifier: Modifier) {
    BasicText(
        "v${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TIME}",
        modifier = modifier.navigationBarsPadding().padding(8.dp),
        style = TextStyle(Paper.copy(alpha = 0.16f), 9.sp, fontFamily = Mono),
    )
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}
