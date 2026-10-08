package com.musediagnostics.stemz.audio

import com.musediagnostics.taal.dsp.AudioFilterEngine
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** One measured point of the heart filter's frequency response. */
data class FilterPoint(val hz: Int, val gainDb: Float)

/**
 * Feeds test sines through a fresh AudioFilterEngine (the same code used for capture) and measures
 * output/input level. Proves the 20–250 / 20–200 Hz filters are active at the real sample rate.
 */
fun measureFilterResponse(sampleRate: Int, filter: HeartFilter): List<FilterPoint> {
    val freqs = intArrayOf(10, 20, 50, 100, 150, 250, 500, 1000, 2000)
    return freqs.map { f ->
        val fe = AudioFilterEngine(sampleRate)
        when (filter) {
            HeartFilter.BASIC -> fe.setPresetFilter(AudioFilterEngine.PresetFilter.HEART)
            HeartFilter.HARD -> fe.setCustomBandpass(filter.lowHz, filter.highHz)
        }
        fe.setPreAmplification(0f)
        val n = sampleRate                      // 1 s; measure the last half (filter settled)
        val amp = 0.05f
        val input = FloatArray(n) { (amp * sin(2.0 * PI * f * it / sampleRate)).toFloat() }
        val out = fe.processBlock(input)
        var si = 0.0
        var so = 0.0
        for (i in n / 2 until n) {
            si += (input[i] * input[i]).toDouble()
            so += (out[i] * out[i]).toDouble()
        }
        FilterPoint(f, (20.0 * log10(sqrt(so / si))).toFloat())
    }
}
