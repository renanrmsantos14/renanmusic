package com.renan.renanmusic

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [uri] é o mediaId da sessão; no Spotify vem como "spotify:track:…" e abre a faixa direto no app. */
data class Track(val title: String = "", val artist: String = "", val art: Bitmap? = null, val duration: Long = 0, val uri: String = "")
data class Playback(
    val playing: Boolean = false,
    val position: Long = 0,
    val updatedAt: Long = 0,
    val speed: Float = 1f,
    /** Ação "curtir" que o Spotify publica na sessão (null se o player não oferece). */
    val like: LikeAction? = null,
)
data class LikeAction(val id: String, val liked: Boolean)
data class QueueEntry(val id: Long, val title: String, val subtitle: String)

/** Lê e controla a sessão de mídia ativa (Spotify primeiro, depois o player que estiver tocando). */
class Player(private val ctx: Context) {
    private val manager = ctx.getSystemService(MediaSessionManager::class.java)
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private val component = ComponentName(ctx, MediaListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var started = false

    private val _track = MutableStateFlow(Track())
    private val _playback = MutableStateFlow(Playback())
    private val _connected = MutableStateFlow(false)
    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    private val _volume = MutableStateFlow(0.5f)
    val track: StateFlow<Track> = _track
    val playback: StateFlow<Playback> = _playback
    val connected: StateFlow<Boolean> = _connected
    val queue: StateFlow<List<QueueEntry>> = _queue
    /** Volume 0..1: do aparelho, ou do dispositivo remoto quando o Spotify toca via Connect. */
    val volume: StateFlow<Float> = _volume

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            // O Spotify às vezes reenvia a mesma faixa sem capa (ao reiniciar/pular): mantém a capa atual.
            val next = metadata.toTrack()
            val cur = _track.value
            _track.value = if (next.art == null && next.title == cur.title && next.artist == cur.artist) next.copy(art = cur.art) else next
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) { _playback.value = state.toPlayback() }
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) { _queue.value = queue.toEntries() }
        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) { _volume.value = readVolume() }
        override fun onSessionDestroyed() { bind(null) }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    // Botões físicos de volume não passam pelo app: acompanha a mudança pela configuração do sistema.
    private val volumeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { _volume.value = readVolume() }
    }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessions, component, main)
            pick(manager.getActiveSessions(component))
            started = true
        } catch (_: SecurityException) {
            _connected.value = false
        }
        ctx.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        _volume.value = readVolume()
    }

    fun stop() {
        ctx.contentResolver.unregisterContentObserver(volumeObserver)
        if (!started) return
        manager.removeOnActiveSessionsChangedListener(sessions)
        bind(null)
        started = false
    }

    private fun pick(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.packageName == SPOTIFY }
            ?: list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) bind(next)
    }

    private fun bind(next: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, main)
        _connected.value = next != null
        _track.value = next?.metadata.toTrack()
        _playback.value = next?.playbackState.toPlayback()
        _queue.value = next?.queue.toEntries()
        _volume.value = readVolume()
    }

    /** Atualiza o estado na hora (ícone e relógio não esperam o Spotify confirmar). */
    fun toggle() {
        val c = controller?.transportControls ?: return
        val pb = _playback.value
        if (pb.playing) c.pause() else c.play()
        val now = SystemClock.elapsedRealtime()
        val pos = if (pb.playing) pb.position + ((now - pb.updatedAt) * pb.speed).toLong() else pb.position
        _playback.value = pb.copy(playing = !pb.playing, position = pos, updatedAt = now)
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun skipTo(id: Long) { controller?.transportControls?.skipToQueueItem(id) }

    fun seek(ms: Long) {
        controller?.transportControls?.seekTo(ms) ?: return
        _playback.value = _playback.value.copy(position = ms, updatedAt = SystemClock.elapsedRealtime())
    }

    fun toggleLike() {
        val like = _playback.value.like ?: return
        controller?.transportControls?.sendCustomAction(like.id, null)
        _playback.value = _playback.value.copy(like = like.copy(liked = !like.liked))
    }

    /** Ajusta o volume sem mostrar a UI do sistema (o app desenha o próprio indicador). */
    fun setVolume(fraction: Float) {
        val f = fraction.coerceIn(0f, 1f)
        val info = controller?.playbackInfo
        if (info?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE && info.maxVolume > 0) {
            controller?.setVolumeTo(Math.round(f * info.maxVolume), 0)
            _volume.value = f
            return
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = Math.round(f * max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        _volume.value = v.toFloat() / max
    }

    private fun readVolume(): Float {
        val info = controller?.playbackInfo
        if (info?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE && info.maxVolume > 0) {
            return info.currentVolume.toFloat() / info.maxVolume
        }
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    /** Abre a faixa atual no Spotify (ou só o app); sem Spotify instalado, abre a página dele na loja. */
    fun openSpotify() {
        val uri = _track.value.uri.takeIf { it.startsWith("spotify:") }
        val intent = uri?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(SPOTIFY) }
            ?: ctx.packageManager.getLaunchIntentForPackage(SPOTIFY)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPOTIFY"))
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            ctx.packageManager.getLaunchIntentForPackage(SPOTIFY)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Sem sessão ativa: pede ao último player de mídia (normalmente o Spotify) para retomar, sem sair do app. */
    fun resume(): Boolean {
        if (!audio.isMusicActive && ctx.packageManager.getLaunchIntentForPackage(SPOTIFY) == null) return false
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
        return true
    }

    private companion object { const val SPOTIFY = "com.spotify.music" }
}

private fun MediaMetadata?.toTrack(): Track = if (this == null) Track() else Track(
    title = getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
    artist = (getString(MediaMetadata.METADATA_KEY_ARTIST) ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
    art = getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: getBitmap(MediaMetadata.METADATA_KEY_ART),
    duration = getLong(MediaMetadata.METADATA_KEY_DURATION),
    uri = getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
)

private fun PlaybackState?.toPlayback(): Playback = if (this == null) Playback() else Playback(
    playing = state == PlaybackState.STATE_PLAYING,
    position = position,
    updatedAt = lastPositionUpdateTime,
    speed = if (playbackSpeed == 0f) 1f else playbackSpeed,
    like = customActions.toLike(),
)

/** O Spotify publica "adicionar/remover das Curtidas" como ação personalizada; o id muda conforme o estado. */
private fun List<PlaybackState.CustomAction>?.toLike(): LikeAction? {
    val a = this?.firstOrNull { c -> LIKE_HINTS.any { c.action.contains(it, ignoreCase = true) } } ?: return null
    val liked = UNLIKE_HINTS.any { a.action.contains(it, ignoreCase = true) }
    return LikeAction(a.action, liked)
}
private val LIKE_HINTS = listOf("COLLECTION", "LIKE", "HEART", "THUMB", "SAVE")
private val UNLIKE_HINTS = listOf("REMOVE", "UNLIKE", "UNHEART", "UNSAVE", "THUMBS_DOWN")

private fun List<MediaSession.QueueItem>?.toEntries(): List<QueueEntry> = this?.map {
    QueueEntry(it.queueId, it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty())
}.orEmpty()
