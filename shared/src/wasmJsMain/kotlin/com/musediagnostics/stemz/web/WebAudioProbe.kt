package com.musediagnostics.stemz.web

import com.musediagnostics.stemz.audio.AudioInputInfo
import com.musediagnostics.stemz.audio.AudioProbe
import com.musediagnostics.stemz.audio.CaptureSettings
import com.musediagnostics.stemz.audio.HeartFilter
import com.musediagnostics.stemz.audio.LevelUpdate
import com.musediagnostics.stemz.audio.LiveTraceBuffer
import com.musediagnostics.taal.dsp.AudioFilterEngine
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** JS glue defined in resources/taal-audio.js. */
external object TaalAudio {
    fun requestPermission(cb: (Boolean) -> Unit)
    fun listInputs(cb: (String) -> Unit)
    fun onDeviceChange(cb: () -> Unit)
    fun start(deviceId: String, onData: (JsAny, Double) -> JsAny?, onError: (String) -> Unit, onEnded: () -> Unit)
    fun trackInfo(): String
    fun stop()
}

private fun f32Length(a: JsAny): Int = js("a.length")
private fun f32Get(a: JsAny, i: Int): Double = js("a[i]")
private fun f32New(n: Int): JsAny = js("new Float32Array(n)")
private fun f32Set(a: JsAny, i: Int, v: Double): Unit = js("a[i] = v")

private data class WebInput(val label: String, val id: String)

/**
 * Web counterpart of IosAudioProbe: getUserMedia + Web Audio, same AudioFilterEngine.
 * TAAL is picked by its label ("MUSE Microphone_TAAL" on iPhone).
 */
class WebAudioProbe : AudioProbe {
    override val platformLabel: String = "Web"
    override val supportsMonitor: Boolean = true

    private var inputs: List<WebInput> = emptyList()
    private var lastRate = 0.0
    private var running = false
    private var settings = CaptureSettings()
    private var filterEngine: AudioFilterEngine? = null
    private var appliedFilter: HeartFilter? = null

    private var routeListener: ((AudioInputInfo) -> Unit)? = null
    private var disconnectListener: (() -> Unit)? = null

    init {
        TaalAudio.onDeviceChange { refreshInputs() }
    }

    private fun taalInput(): WebInput? {
        inputs.firstOrNull { it.label.contains("muse", true) || it.label.contains("taal", true) }?.let { return it }
        inputs.firstOrNull { it.label.contains("usb", true) }?.let { return it }
        if (inputs.size > 1) {
            return inputs.firstOrNull {
                !it.label.contains("iphone", true) && !it.label.contains("built-in", true) &&
                    !it.label.contains("default", true) && it.label != "Unnamed input"
            }
        }
        return null
    }

    private var lastListText: String? = null

    private fun refreshInputs() {
        TaalAudio.listInputs { text ->
            if (text == lastListText) return@listInputs   // polled every 1.5 s: only react to real changes
            lastListText = text
            inputs = text.split('\n').filter { it.isNotBlank() }.map {
                val parts = it.split('\t')
                WebInput(parts[0], parts.getOrElse(1) { "" })
            }
            if (running && taalInput() == null) {
                stop()
                disconnectListener?.invoke()
            }
            routeListener?.invoke(inputInfo())
        }
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        TaalAudio.requestPermission { ok ->
            if (ok) refreshInputs()
            onResult(ok)
        }
    }

    override fun prepareSession(): String? {
        lastListText = null
        refreshInputs()   // async; result arrives through the route listener
        return null
    }

    override fun inputInfo(): AudioInputInfo {
        val taal = taalInput()
        val applied = TaalAudio.trackInfo()
        return AudioInputInfo(
            usbConnected = taal != null,
            deviceName = taal?.label ?: "none",
            portType = if (applied.isNotEmpty()) "Web: $applied" else "Web audioinput",
            sessionSampleRate = lastRate,
            inputChannels = 1,
            availableInputs = inputs.map { it.label },
        )
    }

    override fun setRouteListener(onRouteChanged: (AudioInputInfo) -> Unit) {
        routeListener = onRouteChanged
    }

    override fun updateSettings(settings: CaptureSettings) {
        this.settings = settings
    }

    private fun applySettings(fe: AudioFilterEngine, s: CaptureSettings) {
        if (appliedFilter != s.filter) {
            when (s.filter) {
                HeartFilter.BASIC -> fe.setPresetFilter(AudioFilterEngine.PresetFilter.HEART)
                HeartFilter.HARD -> fe.setCustomBandpass(s.filter.lowHz, s.filter.highHz)
            }
            appliedFilter = s.filter
        }
        fe.setPreAmplification(s.preAmpDb)
        fe.setHumRumbleFilterEnabled(s.humFilter)
    }

    override fun start(
        settings: CaptureSettings,
        onUpdate: (LevelUpdate) -> Unit,
        onError: (String) -> Unit,
        onDeviceDisconnected: () -> Unit,
    ) {
        stop()
        this.settings = settings
        disconnectListener = onDeviceDisconnected
        val taal = taalInput()
        if (taal == null) {
            onError("TAAL not connected. Plug in the stethoscope first.")
            return
        }

        val envLen = 300                  // ~3 s of 10 ms buckets
        val env = FloatArray(envLen)
        var envPos = 0
        var bucketMax = 0f
        var bucketCount = 0
        var reportedInfo = false
        val trace = LiveTraceBuffer()

        running = true
        TaalAudio.start(
            taal.id,
            onData = onData@{ arr, rate ->
                if (!running) return@onData null
                lastRate = rate
                val fe = filterEngine ?: AudioFilterEngine(rate.toInt()).also { filterEngine = it; appliedFilter = null }
                val s = this.settings
                applySettings(fe, s)

                val n = f32Length(arr)
                val raw = FloatArray(n)
                var sumSq = 0.0
                var peak = 0f
                for (i in 0 until n) {
                    val v = f32Get(arr, i).toFloat()
                    raw[i] = v
                    sumSq += (v * v).toDouble()
                    val a = abs(v)
                    if (a > peak) peak = a
                }
                val filtered = fe.processBlock(raw)

                val bucketSize = max(1, (rate / 100.0).toInt())
                val undo = 1f / 10.0.pow(s.preAmpDb / 20.0).toFloat()
                for (i in 0 until n) {
                    val a = abs(filtered[i]) * undo
                    if (a > bucketMax) bucketMax = a
                    bucketCount++
                    if (bucketCount >= bucketSize) {
                        env[envPos] = bucketMax
                        envPos = (envPos + 1) % envLen
                        bucketMax = 0f
                        bucketCount = 0
                    }
                }
                trace.push(filtered, rate, undo)
                val (tMin, tMax) = trace.snapshot()

                var envMax = 0.01f
                for (v in env) if (v > envMax) envMax = v
                val rms = if (n > 0) sqrt(sumSq / n) else 0.0
                onUpdate(
                    LevelUpdate(
                        rmsDbfs = toDb(rms.toFloat()),
                        peakDbfs = toDb(peak),
                        captureSampleRate = rate,
                        framesPerBuffer = n,
                        envelope = FloatArray(envLen) { env[(envPos + it) % envLen] / envMax },
                        traceMin = tMin,
                        traceMax = tMax,
                    )
                )
                if (!reportedInfo) {
                    reportedInfo = true
                    routeListener?.invoke(inputInfo())
                }

                // Live monitor: hand the filtered block back to JS for playback (Android AudioTrack monitor).
                if (s.monitor) {
                    val out = f32New(n)
                    for (i in 0 until n) f32Set(out, i, filtered[i].toDouble())
                    out
                } else {
                    null
                }
            },
            onError = { msg -> running = false; onError("Microphone: $msg") },
            onEnded = { running = false; onDeviceDisconnected() },
        )
    }

    override fun stop() {
        running = false
        TaalAudio.stop()
        filterEngine = null
    }

    private fun toDb(x: Float): Float = if (x <= 1e-9f) -90f else (20f * log10(x)).coerceAtLeast(-90f)
}
