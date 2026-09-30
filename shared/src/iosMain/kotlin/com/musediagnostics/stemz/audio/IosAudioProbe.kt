package com.musediagnostics.stemz.audio

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.*
import platform.Foundation.NSError
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * iOS counterpart of taal-core's TaalAudioCapture presence check + capture loop (diagnostics only).
 * - Measurement mode disables AGC / noise suppression (like Android's UNPROCESSED source).
 * - Prefers the USB audio input when one is present (like setPreferredDevice on Android).
 */
class IosAudioProbe : AudioProbe {
    private val session = AVAudioSession.sharedInstance()
    private var engine: AVAudioEngine? = null

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        session.requestRecordPermission { granted ->
            dispatch_async(dispatch_get_main_queue()) { onResult(granted) }
        }
    }

    override fun prepareSession(): String? = memScoped {
        val err = alloc<ObjCObjectVar<NSError?>>()
        if (!session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                AVAudioSessionModeMeasurement,
                AVAudioSessionCategoryOptionDefaultToSpeaker,
                err.ptr,
            )
        ) return@memScoped "setCategory: ${err.value?.localizedDescription}"

        session.setPreferredSampleRate(44100.0, err.ptr)

        if (!session.setActive(true, err.ptr)) {
            return@memScoped "setActive: ${err.value?.localizedDescription}"
        }

        val usb = usbPort()
        if (usb != null && !session.setPreferredInput(usb, err.ptr)) {
            return@memScoped "setPreferredInput: ${err.value?.localizedDescription}"
        }
        null
    }

    private fun inputs(): List<AVAudioSessionPortDescription> =
        session.availableInputs?.filterIsInstance<AVAudioSessionPortDescription>() ?: emptyList()

    private fun usbPort(): AVAudioSessionPortDescription? =
        inputs().firstOrNull { it.portType == AVAudioSessionPortUSBAudio }

    override fun inputInfo(): AudioInputInfo {
        val current = session.currentRoute.inputs
            .filterIsInstance<AVAudioSessionPortDescription>().firstOrNull()
        return AudioInputInfo(
            usbConnected = current?.portType == AVAudioSessionPortUSBAudio || usbPort() != null,
            deviceName = current?.portName ?: "none",
            portType = current?.portType ?: "none",
            sessionSampleRate = session.sampleRate,
            inputChannels = session.inputNumberOfChannels,
            availableInputs = inputs().map { "${it.portName} (${it.portType})" },
        )
    }

    override fun start(onUpdate: (LevelUpdate) -> Unit, onError: (String) -> Unit) {
        stop()
        val eng = AVAudioEngine()
        val input = eng.inputNode
        val format = input.outputFormatForBus(0uL)
        val rate = format.sampleRate
        if (rate <= 0.0) {
            onError("Input format has 0 Hz sample rate (no input available?)")
            return
        }

        val bucketSize = max(1, (rate / 100.0).toInt())   // 10 ms buckets
        val envLen = 300                                   // ~3 s
        val env = FloatArray(envLen)
        var envPos = 0
        var bucketMax = 0f
        var bucketCount = 0

        input.installTapOnBus(0uL, 4096u, format) { buffer: AVAudioPCMBuffer?, _ ->
            val buf = buffer ?: return@installTapOnBus
            val n = buf.frameLength.toInt()
            val ch0 = buf.floatChannelData?.get(0) ?: return@installTapOnBus
            var sumSq = 0.0
            var peak = 0f
            for (i in 0 until n) {
                val v = ch0[i]
                val a = abs(v)
                sumSq += (v * v).toDouble()
                if (a > peak) peak = a
                if (a > bucketMax) bucketMax = a
                bucketCount++
                if (bucketCount >= bucketSize) {
                    env[envPos] = bucketMax
                    envPos = (envPos + 1) % envLen
                    bucketMax = 0f
                    bucketCount = 0
                }
            }
            val rms = if (n > 0) sqrt(sumSq / n) else 0.0
            val ordered = FloatArray(envLen) { env[(envPos + it) % envLen] }
            val update = LevelUpdate(
                rmsDbfs = toDb(rms.toFloat()),
                peakDbfs = toDb(peak),
                captureSampleRate = rate,
                framesPerBuffer = n,
                envelope = ordered,
            )
            dispatch_async(dispatch_get_main_queue()) { onUpdate(update) }
        }

        eng.prepare()
        memScoped {
            val err = alloc<ObjCObjectVar<NSError?>>()
            if (!eng.startAndReturnError(err.ptr)) {
                input.removeTapOnBus(0uL)
                onError("engine start: ${err.value?.localizedDescription}")
                return
            }
        }
        engine = eng
    }

    override fun stop() {
        engine?.let {
            it.inputNode.removeTapOnBus(0uL)
            it.stop()
        }
        engine = null
    }

    private fun toDb(x: Float): Float = if (x <= 1e-9f) -90f else (20f * log10(x)).coerceAtLeast(-90f)
}
