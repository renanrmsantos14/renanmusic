package com.renan.renanmusic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface Update {
    data object None : Update
    /** APK novo já baixado, esperando o toque para instalar. */
    data class Ready(val version: String, val file: File) : Update
    data object Installing : Update
}

/**
 * Autoatualização pelo GitHub Releases: ao abrir o app, consulta o último release,
 * baixa o APK em segundo plano e oferece instalar. O Android pede confirmação só
 * quando não permite a autoatualização silenciosa.
 */
object Updater {
    private val _state = MutableStateFlow<Update>(Update.None)
    val state: StateFlow<Update> = _state
    private var lastCheck = 0L

    suspend fun check(ctx: Context) {
        val now = SystemClock.elapsedRealtime()
        if (_state.value != Update.None || (lastCheck != 0L && now - lastCheck < CHECK_EVERY_MS)) return
        lastCheck = now
        val ready = withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"))
                val version = json.getString("tag_name").removePrefix("v")
                if (!newer(version, BuildConfig.VERSION_NAME)) return@runCatching null
                val assets = json.getJSONArray("assets")
                val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
                    ?: return@runCatching null
                val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "RenanMusic-v$version.apk")
                if (!file.exists()) {
                    dir.listFiles()?.forEach { it.delete() } // APKs antigos
                    val tmp = File(dir, "download.tmp")
                    open(url).inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(file)
                }
                Update.Ready(version, file)
            }.getOrNull()
        }
        if (ready != null) _state.value = ready
    }

    fun install(ctx: Context) {
        val ready = _state.value as? Update.Ready ?: return
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) {
            // Primeira vez: o Android exige liberar "instalar apps desconhecidos" para este app.
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        _state.value = Update.Installing
        runCatching {
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, ready.file.length()).use { out ->
                    ready.file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val status = PendingIntent.getBroadcast(
                    ctx, id, Intent(ctx, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
            }
        }.onFailure { _state.value = ready }
    }

    /** Instalação cancelada ou falhou: volta a oferecer o botão. */
    internal fun failed() {
        if (_state.value == Update.Installing) _state.value = Update.None
        lastCheck = 0L
    }

    internal fun newer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(remote); val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }; val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "RenanMusic/${BuildConfig.VERSION_NAME} (Android)")
        setRequestProperty("Accept", "application/vnd.github+json")
        if (responseCode !in 200..299) error("HTTP $responseCode")
    }

    private fun get(url: String) = open(url).run { try { inputStream.bufferedReader().readText() } finally { disconnect() } }

    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
}

/** Recebe o resultado da instalação; se o Android pedir confirmação, abre a tela dele. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> Unit // o app é reiniciado pelo sistema
            else -> Updater.failed()
        }
    }
}
