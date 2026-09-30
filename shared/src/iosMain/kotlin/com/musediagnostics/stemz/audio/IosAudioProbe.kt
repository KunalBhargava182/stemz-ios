package com.musediagnostics.stemz.audio

import com.musediagnostics.taal.dsp.AudioFilterEngine
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.AVFAudio.*
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * iOS counterpart of taal-core's TaalAudioCapture + TaalRecorder live path.
 * - Measurement mode disables AGC / noise suppression (like Android's UNPROCESSED source).
 * - Prefers the USB audio input (like setPreferredDevice on Android).
 * - Route-change observer replaces TaalConnectionBroadcastReceiver.
 * - Filtered monitor via AVAudioPlayerNode replaces the Android AudioTrack monitor.
 */
class IosAudioProbe : AudioProbe {
    private val session = AVAudioSession.sharedInstance()
    private var engine: AVAudioEngine? = null
    private var player: AVAudioPlayerNode? = null
    private var filterEngine: AudioFilterEngine? = null
    private var appliedFilter: HeartFilter? = null

    @kotlin.concurrent.Volatile private var settings = CaptureSettings()

    private var routeListener: ((AudioInputInfo) -> Unit)? = null
    private var disconnectListener: (() -> Unit)? = null
    private val observers = mutableListOf<NSObjectProtocol>()

    init {
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(
            AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> onRouteChange() }
        observers += center.addObserverForName(
            AVAudioEngineConfigurationChangeNotification, null, NSOperationQueue.mainQueue,
        ) { _: NSNotification? -> onRouteChange() }
    }

    override fun setRouteListener(onRouteChanged: (AudioInputInfo) -> Unit) {
        routeListener = onRouteChanged
    }

    private fun onRouteChange() {
        if (engine != null) {
            // Capturing: if TAAL disappeared, stop instead of silently falling back to the phone mic.
            if (!currentInputIsUsb()) {
                stop()
                disconnectListener?.invoke()
            }
        } else {
            prepareSession()   // re-selects USB input when TAAL is plugged back in
        }
        routeListener?.invoke(inputInfo())
    }

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
                AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetoothA2DP,
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

    private fun currentInput(): AVAudioSessionPortDescription? =
        session.currentRoute.inputs.filterIsInstance<AVAudioSessionPortDescription>().firstOrNull()

    private fun currentInputIsUsb(): Boolean = currentInput()?.portType == AVAudioSessionPortUSBAudio

    override fun inputInfo(): AudioInputInfo {
        val current = currentInput()
        return AudioInputInfo(
            usbConnected = currentInputIsUsb(),
            deviceName = current?.portName ?: "none",
            portType = current?.portType ?: "none",
            sessionSampleRate = session.sampleRate,
            inputChannels = session.inputNumberOfChannels,
            availableInputs = inputs().map { "${it.portName} (${it.portType})" },
        )
    }

    override fun updateSettings(settings: CaptureSettings) {
        this.settings = settings
    }

    /** Applies settings to the filter engine; called on the audio thread before each block. */
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

        if (!currentInputIsUsb()) {
            onError("TAAL not connected. Plug in the stethoscope first.")
            return
        }

        val eng = AVAudioEngine()
        val input = eng.inputNode
        val format = input.outputFormatForBus(0uL)
        val rate = format.sampleRate
        if (rate <= 0.0) {
            onError("Input format has 0 Hz sample rate (no input available?)")
            return
        }

        // DSP runs at the real capture rate (48 kHz on iPhone), not Android's fixed 44.1 kHz.
        val fe = AudioFilterEngine(rate.toInt())
        appliedFilter = null
        filterEngine = fe

        // Monitor: filtered mono signal -> mixer -> speaker / Bluetooth.
        val monoFmt = AVAudioFormat(standardFormatWithSampleRate = rate, channels = 1u)
        val pl = AVAudioPlayerNode()
        eng.attachNode(pl)
        eng.connect(pl, eng.mainMixerNode, monoFmt)

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
            val s = this.settings
            applySettings(fe, s)

            val raw = FloatArray(n)
            var sumSq = 0.0
            var peak = 0f
            for (i in 0 until n) {
                val v = ch0[i]
                raw[i] = v
                sumSq += (v * v).toDouble()
                val a = abs(v)
                if (a > peak) peak = a
            }

            val filtered = fe.processBlock(raw)

            // Display: undo pre-amp so the trace shows true acoustic level (Android COMPENSATE_PREAMP_IN_DISPLAY).
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

            if (s.monitor) {
                val out = AVAudioPCMBuffer(monoFmt, n.toUInt())
                if (out != null) {
                    out.frameLength = n.toUInt()
                    val dst = out.floatChannelData?.get(0)
                    if (dst != null) {
                        for (i in 0 until n) dst[i] = filtered[i]
                        pl.scheduleBuffer(out, null)
                    }
                }
            }

            var envMax = 0.01f
            for (v in env) if (v > envMax) envMax = v
            val ordered = FloatArray(envLen) { env[(envPos + it) % envLen] / envMax }
            val rms = if (n > 0) sqrt(sumSq / n) else 0.0
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
        pl.play()
        engine = eng
        player = pl
    }

    override fun stop() {
        engine?.let {
            it.inputNode.removeTapOnBus(0uL)
            player?.stop()
            it.stop()
        }
        engine = null
        player = null
        filterEngine = null
    }

    private fun toDb(x: Float): Float = if (x <= 1e-9f) -90f else (20f * log10(x)).coerceAtLeast(-90f)
}
