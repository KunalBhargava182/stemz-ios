package com.musediagnostics.stemz.audio

import kotlin.math.abs
import kotlin.math.max

/**
 * Rolling min/max trace for the live waveform (same idea as Android PcgScaleWaveformView.downsampleMinMax).
 * Keeps the last [windowSeconds] in [bucketMs] buckets so peaks are never lost, with a smoothed auto-scale.
 */
class LiveTraceBuffer(
    private val windowSeconds: Double = 4.0,   // PcgTimeScale.DEFAULT_VISIBLE_SECONDS
    private val bucketMs: Double = 5.0,
) {
    private var rate = 0.0
    private var bucketSize = 1
    private var len = 0
    private var mins = FloatArray(0)
    private var maxs = FloatArray(0)
    private var pos = 0
    private var bMin = 0f
    private var bMax = 0f
    private var bCount = 0
    private var scale = 0.05f

    fun push(samples: FloatArray, sampleRate: Double, gain: Float) {
        if (sampleRate != rate) {
            rate = sampleRate
            bucketSize = max(1, (rate * bucketMs / 1000.0).toInt())
            len = (windowSeconds * 1000.0 / bucketMs).toInt()
            mins = FloatArray(len); maxs = FloatArray(len)
            pos = 0; bCount = 0; bMin = 0f; bMax = 0f
        }
        for (v in samples) {
            val x = v * gain
            if (bCount == 0) { bMin = x; bMax = x } else {
                if (x < bMin) bMin = x
                if (x > bMax) bMax = x
            }
            bCount++
            if (bCount >= bucketSize) {
                mins[pos] = bMin; maxs[pos] = bMax
                pos = (pos + 1) % len
                bCount = 0
            }
        }
    }

    /** Oldest-first (min, max) arrays scaled to about -1..1. */
    fun snapshot(): Pair<FloatArray, FloatArray> {
        if (len == 0) return FloatArray(0) to FloatArray(0)
        var peak = 0f
        for (i in 0 until len) {
            peak = max(peak, max(abs(mins[i]), abs(maxs[i])))
        }
        val target = max(peak, 0.002f)
        scale += (target - scale) * 0.25f
        val k = 1f / (scale * 1.15f)
        val outMin = FloatArray(len) { mins[(pos + it) % len] * k }
        val outMax = FloatArray(len) { maxs[(pos + it) % len] * k }
        return outMin to outMax
    }
}
