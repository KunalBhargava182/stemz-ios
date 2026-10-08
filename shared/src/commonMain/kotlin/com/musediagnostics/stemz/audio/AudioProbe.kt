package com.musediagnostics.stemz.audio

/** Snapshot of the current audio input route, as reported by the OS. */
data class AudioInputInfo(
    val usbConnected: Boolean,
    val deviceName: String,
    val portType: String,
    val sessionSampleRate: Double,
    val inputChannels: Long,
    val availableInputs: List<String>,
)

/** One UI update from the capture thread. */
data class LevelUpdate(
    val rmsDbfs: Float,
    val peakDbfs: Float,
    val captureSampleRate: Double,
    val framesPerBuffer: Int,
    /** Recent filtered envelope (max |x| per 10 ms bucket), oldest first, auto-scaled to 0..1. */
    val envelope: FloatArray,
    /** Optional live waveform (min/max per 5 ms, last 4 s, scaled to -1..1). Empty = draw envelope. */
    val traceMin: FloatArray = FloatArray(0),
    val traceMax: FloatArray = FloatArray(0),
    /** Peak of the monitor signal you hear, after the listen volume. -90 when unknown. */
    val outputPeakDbfs: Float = -90f,
)

/** Mirrors the Android recorder's heart filter choices (PcgScaleRecordingFragment). */
enum class HeartFilter(val label: String, val lowHz: Double, val highHz: Double) {
    BASIC("Basic 20–250 Hz", 20.0, 250.0),   // PreFilter.HEART
    HARD("Hard 20–200 Hz", 20.0, 200.0),     // HEART_HARD (custom bandpass)
}

/** Live DSP / monitor settings; read by the audio thread on every buffer. */
data class CaptureSettings(
    val filter: HeartFilter = HeartFilter.BASIC,
    val preAmpDb: Float = 10f,
    val humFilter: Boolean = false,
    val monitor: Boolean = false,
    /** Extra gain applied only to what you hear (not to the graph or saved audio). Web only for now. */
    val monitorGainDb: Float = 24f,
)

interface AudioProbe {
    /** Shown in the title, e.g. "iOS" or "Web". */
    val platformLabel: String get() = "iOS"
    /** False hides the Listen switch. */
    val supportsMonitor: Boolean get() = true
    /** True shows the "Listen volume" slider (web; Safari captures much quieter than the native app). */
    val supportsMonitorGain: Boolean get() = false

    fun requestPermission(onResult: (Boolean) -> Unit)
    fun prepareSession(): String?          // returns error message or null
    fun inputInfo(): AudioInputInfo

    /** Called on the main thread whenever the OS audio route changes (plug / unplug). */
    fun setRouteListener(onRouteChanged: (AudioInputInfo) -> Unit)

    fun start(
        settings: CaptureSettings,
        onUpdate: (LevelUpdate) -> Unit,
        onError: (String) -> Unit,
        onDeviceDisconnected: () -> Unit,
    )
    fun updateSettings(settings: CaptureSettings)
    fun stop()
}
