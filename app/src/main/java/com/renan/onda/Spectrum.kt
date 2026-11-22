package com.renan.onda

import android.media.audiofx.Visualizer
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** FFT do áudio de saída do aparelho (sessão 0), reduzida a bandas logarítmicas 0..1. */
class Spectrum {
    @Volatile var bands = FloatArray(BANDS)
        private set

    private var visualizer: Visualizer? = null
    private var edges = IntArray(BANDS + 1)
    private var peak = 0.2f

    fun start() {
        if (visualizer != null) return
        visualizer = runCatching {
            Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
                edges = logEdges(captureSize / 2)
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(v: Visualizer?, data: ByteArray?, rate: Int) = Unit
                    override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) { fft?.let(::process) }
                }, Visualizer.getMaxCaptureRate(), false, true)
                enabled = true
            }
        }.getOrNull()
    }

    fun stop() {
        visualizer?.release()
        visualizer = null
        bands = FloatArray(BANDS)
    }

    private fun process(fft: ByteArray) {
        val out = FloatArray(BANDS)
        var loudest = 0f
        for (b in 0 until BANDS) {
            var sum = 0f
            for (k in edges[b] until edges[b + 1]) sum += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
            val v = ln(1f + sum / (edges[b + 1] - edges[b])) / ln(65f)
            out[b] = v
            loudest = max(loudest, v)
        }
        // Ganho adaptativo: o nível do Visualizer varia com o volume e com o aparelho.
        peak = max(peak * 0.992f, loudest).coerceAtLeast(0.15f)
        for (b in 0 until BANDS) out[b] = (out[b] / peak).coerceIn(0f, 1f).let { it * it }
        bands = out
    }

    private fun logEdges(bins: Int): IntArray {
        val lo = 2.0
        val hi = bins * 0.5 // até ~11 kHz
        val e = IntArray(BANDS + 1) { i -> (lo * (hi / lo).pow(i / BANDS.toDouble())).toInt() }
        for (i in 1..BANDS) if (e[i] <= e[i - 1]) e[i] = e[i - 1] + 1
        return e
    }

    companion object { const val BANDS = 24 }
}
