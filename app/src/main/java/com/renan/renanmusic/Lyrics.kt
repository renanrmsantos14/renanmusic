package com.renan.renanmusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricLine(val at: Long, val text: String)

/** Letras sincronizadas do LRCLIB (público, sem chave). O Spotify não expõe letra pela sessão de mídia. */
object Lyrics {
    private val cache = HashMap<String, List<LyricLine>>()
    private val noise = Regex("""\s*(\(feat\.?[^)]*\)|\[feat\.?[^\]]*]|-\s*(\d{4}\s*)?remaster(ed)?.*|-\s*ao vivo.*|-\s*live.*)""", RegexOption.IGNORE_CASE)
    private val timestamp = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")

    suspend fun fetch(title: String, artist: String, durationMs: Long): List<LyricLine> {
        if (title.isBlank()) return emptyList()
        val key = "$artist|$title"
        cache[key]?.let { return it }
        val lines = withContext(Dispatchers.IO) {
            // 1) busca exata com duração; 2) sem duração; 3) título limpo ("- Remastered", "(feat. …)").
            val clean = title.replace(noise, "").trim()
            runCatching {
                get(title, artist, durationMs) ?: get(title, artist, 0)
                    ?: clean.takeIf { it != title && it.isNotEmpty() }?.let { get(it, artist, 0) }
                    ?: emptyList()
            }.getOrNull()
        }
        // Falha de rede não entra no cache: a próxima abertura tenta de novo.
        if (lines != null) cache[key] = lines
        return lines.orEmpty()
    }

    /** null = não encontrada ou sem letra sincronizada; exceção = erro de rede. */
    private fun get(title: String, artist: String, durationMs: Long): List<LyricLine>? {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        var url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
        if (durationMs > 0) url += "&duration=${durationMs / 1000}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
            setRequestProperty("User-Agent", "RenanMusic/${BuildConfig.VERSION_NAME} (Android)")
        }
        return try {
            when (conn.responseCode) {
                200 -> parse(JSONObject(conn.inputStream.bufferedReader().readText()).optString("syncedLyrics")).ifEmpty { null }
                404 -> null
                else -> error("HTTP ${conn.responseCode}")
            }
        } finally { conn.disconnect() }
    }

    private fun parse(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { raw ->
        val stamps = timestamp.findAll(raw).toList()
        val text = raw.replace(timestamp, "").trim()
        stamps.map { m ->
            val (min, sec, frac) = m.destructured
            val ms = frac.padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
            LyricLine(min.toLong() * 60_000 + sec.toLong() * 1000 + ms, text)
        }
    }.sortedBy { it.at }.toList()
}
