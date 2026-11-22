package com.renan.onda

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Track(val title: String = "", val artist: String = "", val art: Bitmap? = null, val duration: Long = 0)
data class Playback(val playing: Boolean = false, val position: Long = 0, val updatedAt: Long = 0, val speed: Float = 1f)
data class QueueEntry(val id: Long, val title: String, val subtitle: String)

/** Lê e controla a sessão de mídia ativa (Spotify primeiro, qualquer outro player como fallback). */
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
    private val _volume = MutableStateFlow(readVolume())
    val track: StateFlow<Track> = _track
    val playback: StateFlow<Playback> = _playback
    val connected: StateFlow<Boolean> = _connected
    val queue: StateFlow<List<QueueEntry>> = _queue
    /** Volume de mídia do aparelho, 0..1. */
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
        override fun onSessionDestroyed() { bind(null) }
    }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessions, component, main)
            pick(manager.getActiveSessions(component))
            started = true
        } catch (_: SecurityException) {
            _connected.value = false
        }
        _volume.value = readVolume()
    }

    fun stop() {
        if (!started) return
        manager.removeOnActiveSessionsChangedListener(sessions)
        bind(null)
        started = false
    }

    private fun pick(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.packageName == SPOTIFY } ?: list?.firstOrNull()
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
    }

    fun toggle() {
        val c = controller?.transportControls ?: return
        if (_playback.value.playing) c.pause() else c.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    fun skipTo(id: Long) { controller?.transportControls?.skipToQueueItem(id) }

    fun seek(ms: Long) {
        controller?.transportControls?.seekTo(ms) ?: return
        _playback.value = _playback.value.copy(position = ms, updatedAt = SystemClock.elapsedRealtime())
    }

    /** Ajusta o volume de mídia sem mostrar a UI do sistema (o app desenha o próprio indicador). */
    fun setVolume(fraction: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (fraction.coerceIn(0f, 1f) * max).let { Math.round(it) }
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        _volume.value = v.toFloat() / max
    }

    private fun readVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun openSpotify() {
        val intent = ctx.packageManager.getLaunchIntentForPackage(SPOTIFY) ?: return
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object { const val SPOTIFY = "com.spotify.music" }
}

private fun MediaMetadata?.toTrack(): Track = if (this == null) Track() else Track(
    title = getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
    artist = (getString(MediaMetadata.METADATA_KEY_ARTIST) ?: getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
    art = getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: getBitmap(MediaMetadata.METADATA_KEY_ART),
    duration = getLong(MediaMetadata.METADATA_KEY_DURATION),
)

private fun PlaybackState?.toPlayback(): Playback = if (this == null) Playback() else Playback(
    playing = state == PlaybackState.STATE_PLAYING,
    position = position,
    updatedAt = lastPositionUpdateTime,
    speed = if (playbackSpeed == 0f) 1f else playbackSpeed,
)

private fun List<MediaSession.QueueItem>?.toEntries(): List<QueueEntry> = this?.map {
    QueueEntry(it.queueId, it.description.title?.toString().orEmpty(), it.description.subtitle?.toString().orEmpty())
}.orEmpty()
