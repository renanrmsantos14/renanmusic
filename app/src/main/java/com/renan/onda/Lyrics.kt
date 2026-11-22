package com.renan.onda

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
    private val timestamp = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")

    suspend fun fetch(title: String, artist: String, durationMs: Long): List<LyricLine> {
        if (title.isBlank()) return emptyList()
        val key = "$artist|$title"
        cache[key]?.let { return it }
        val lines = withContext(Dispatchers.IO) {
            runCatching {
                fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
                var url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
                if (durationMs > 0) url += "&duration=${durationMs / 1000}"
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 5000
                    setRequestProperty("User-Agent", "Onda/${BuildConfig.VERSION_NAME} (Android)")
                }
                try {
                    if (conn.responseCode != 200) emptyList()
                    else parse(JSONObject(conn.inputStream.bufferedReader().readText()).optString("syncedLyrics"))
                } finally { conn.disconnect() }
            }.getOrDefault(emptyList())
        }
        cache[key] = lines
        return lines
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
