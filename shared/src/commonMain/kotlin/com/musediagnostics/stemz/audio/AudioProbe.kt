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
    /** Recent envelope (max |x| per 10 ms bucket), oldest first, values 0..1. */
    val envelope: FloatArray,
)

/**
 * Platform audio input used by the diagnostics screen.
 * iOS: AVAudioSession + AVAudioEngine (see iosMain).
 */
interface AudioProbe {
    fun requestPermission(onResult: (Boolean) -> Unit)
    fun prepareSession(): String?          // returns error message or null
    fun inputInfo(): AudioInputInfo
    fun start(onUpdate: (LevelUpdate) -> Unit, onError: (String) -> Unit)
    fun stop()
}
