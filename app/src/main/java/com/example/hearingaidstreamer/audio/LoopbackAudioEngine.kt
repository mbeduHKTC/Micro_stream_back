package com.example.hearingaidstreamer.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Captures microphone audio, applies the narrow-band filtering pipeline, and replays it while
 * emitting an envelope signal for visualisation.
 */
class LoopbackAudioEngine(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    data class Settings(
        val includeMurmurs: Boolean = false,
        val mainsFrequencyHz: Int = 50,
        val gainMultiplier: Float = 1f,
        val preferredSampleRate: Int = 2000
    )

    private val settingsRef = AtomicReference(Settings())
    private val _envelopeFlow = MutableSharedFlow<Float>(extraBufferCapacity = 512)
    val envelopeFlow: SharedFlow<Float> = _envelopeFlow

    private var job: Job? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var muted: Boolean = false

    private val preferredInputDeviceProvider = AtomicReference<(() -> AudioDeviceInfo?)?>(null)

    @Volatile
    private var filtersDirty: Boolean = true

    fun updateSettings(block: (Settings) -> Settings) {
        settingsRef.updateAndGet { current -> block(current) }
        filtersDirty = true
    }

    fun currentSettings(): Settings = settingsRef.get()

    fun start(scope: CoroutineScope) {
        if (job != null) return
        val settings = settingsRef.get()
        val pipes = createAudioPipes(settings) ?: return
        val sampleRate = pipes.sampleRate
        val bufferSize = pipes.bufferSize
        val record = pipes.record
        val track = pipes.track

        track.play()
        track.setVolume(if (muted) 0f else 1f)
        record.startRecording()

        audioRecord = record
        audioTrack = track

        job = scope.launch(dispatcher) {
            val filterChainFactory = FilterChainFactory(sampleRate)
            var localSettings = settings
            var filterChain = filterChainFactory.create(localSettings)
            var envelope = EnvelopeDetector(sampleRate)

            val buffer = ShortArray(bufferSize)
            val publishWindow = max(sampleRate / 25, 1)
            var accumulator = 0f
            var accumulatorCount = 0

            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val currentSettings = settingsRef.get()
                if (filtersDirty || currentSettings != localSettings) {
                    filterChain = filterChainFactory.create(currentSettings)
                    envelope = EnvelopeDetector(sampleRate)
                    localSettings = currentSettings
                    filtersDirty = false
                }

                var i = 0
                while (i < read) {
                    val raw = buffer[i] / Short.MAX_VALUE.toFloat()
                    val filtered = filterChain.process(raw)
                    val amplified = (filtered * localSettings.gainMultiplier).coerceIn(-1f, 1f)
                    val output = if (muted) 0f else amplified
                    buffer[i] = (output * Short.MAX_VALUE.toFloat()).toInt().coerceIn(-32768, 32767).toShort()

                    val env = envelope.process(amplified)
                    accumulator += env
                    accumulatorCount++
                    if (accumulatorCount >= publishWindow) {
                        val avg = accumulator / accumulatorCount
                        _envelopeFlow.tryEmit(avg)
                        accumulator = 0f
                        accumulatorCount = 0
                    }
                    i++
                }

                track.write(buffer, 0, read)
            }
        }
    }

    suspend fun stop() {
        job?.let { existing ->
            withContext(dispatcher) {
                existing.cancelAndJoin()
            }
        }
        job = null

        audioRecord?.apply {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        audioTrack?.apply {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        audioRecord = null
        audioTrack = null
        filtersDirty = true
        muted = false
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        audioTrack?.setVolume(if (muted) 0f else 1f)
    }

    fun isMuted(): Boolean = muted

    fun setPreferredInputDeviceProvider(provider: (() -> AudioDeviceInfo?)?) {
        preferredInputDeviceProvider.set(provider)
        val preferredDevice = provider?.invoke()
        audioRecord?.setPreferredDevice(preferredDevice)
    }

    private fun createAudioPipes(settings: Settings): AudioPipes? {
        val preferred = settings.preferredSampleRate
        val candidates = listOf(preferred, 2000, 4000, 8000, 16000, 44100).distinct()
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        for (rate in candidates) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
            if (minBuffer <= 0) continue
            val bufferSize = max(minBuffer, rate)
            var record: AudioRecord? = null
            var track: AudioTrack? = null
            try {
                record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(audioFormat)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize * 2)
                    .build()

                preferredInputDeviceProvider.get()?.invoke()?.let { device ->
                    record.setPreferredDevice(device)
                }

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(audioFormat)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                return AudioPipes(rate, bufferSize, record, track)
            } catch (error: IllegalArgumentException) {
                record?.release()
                track?.release()
            }
        }
        return null
    }

    private data class AudioPipes(
        val sampleRate: Int,
        val bufferSize: Int,
        val record: AudioRecord,
        val track: AudioTrack
    )

    private class FilterChainFactory(private val sampleRate: Int) {
        fun create(settings: Settings): FilterChain {
            val cutoffHigh = 20f
            val cutoffLow = if (settings.includeMurmurs) 400f else 150f
            val notchFreq = settings.mainsFrequencyHz.toFloat()
            return FilterChain(sampleRate.toFloat(), cutoffHigh, cutoffLow, notchFreq)
        }
    }

    private class FilterChain(
        private val sampleRate: Float,
        highPassCutoff: Float,
        lowPassCutoff: Float,
        notchFrequency: Float
    ) {
        private val hpSections = arrayOf(
            Biquad.highPass(sampleRate, highPassCutoff, Q1),
            Biquad.highPass(sampleRate, highPassCutoff, Q2)
        )
        private val notch = Biquad.notch(sampleRate, notchFrequency, 35f)
        private val lpSections = arrayOf(
            Biquad.lowPass(sampleRate, lowPassCutoff, Q1),
            Biquad.lowPass(sampleRate, lowPassCutoff, Q2)
        )

        fun process(sample: Float): Float {
            var value = sample
            hpSections.forEach { value = it.process(value) }
            value = notch.process(value)
            lpSections.forEach { value = it.process(value) }
            return value
        }

        companion object {
            private const val Q1 = 0.5411961f
            private const val Q2 = 1.306563f
        }
    }

    private class EnvelopeDetector(sampleRate: Int) {
        private val lowPass = Biquad.lowPass(sampleRate.toFloat(), 8f, 0.707f)
        fun process(sample: Float): Float {
            val rectified = abs(sample)
            return lowPass.process(rectified)
        }
    }

    private class Biquad private constructor(
        private var sampleRate: Float,
        private var frequency: Float,
        private var q: Float,
        private val type: Type
    ) {
        private var a1 = 0f
        private var a2 = 0f
        private var b0 = 0f
        private var b1 = 0f
        private var b2 = 0f
        private var z1 = 0f
        private var z2 = 0f

        init {
            recalculate()
        }

        fun process(input: Float): Float {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }

        private fun recalculate() {
            val omega = 2f * PI.toFloat() * frequency / sampleRate
            val sin = sin(omega)
            val cos = cos(omega)
            val alpha = sin / (2f * q)
            when (type) {
                Type.LOW_PASS -> {
                    val b0Raw = (1f - cos) / 2f
                    val b1Raw = 1f - cos
                    val b2Raw = (1f - cos) / 2f
                    val a0 = 1f + alpha
                    val a1Raw = -2f * cos
                    val a2Raw = 1f - alpha
                    setCoefficients(b0Raw, b1Raw, b2Raw, a0, a1Raw, a2Raw)
                }
                Type.HIGH_PASS -> {
                    val b0Raw = (1f + cos) / 2f
                    val b1Raw = -(1f + cos)
                    val b2Raw = (1f + cos) / 2f
                    val a0 = 1f + alpha
                    val a1Raw = -2f * cos
                    val a2Raw = 1f - alpha
                    setCoefficients(b0Raw, b1Raw, b2Raw, a0, a1Raw, a2Raw)
                }
                Type.NOTCH -> {
                    val b0Raw = 1f
                    val b1Raw = -2f * cos
                    val b2Raw = 1f
                    val a0 = 1f + alpha
                    val a1Raw = -2f * cos
                    val a2Raw = 1f - alpha
                    setCoefficients(b0Raw, b1Raw, b2Raw, a0, a1Raw, a2Raw)
                }
            }
        }

        private fun setCoefficients(b0Raw: Float, b1Raw: Float, b2Raw: Float, a0: Float, a1Raw: Float, a2Raw: Float) {
            val invA0 = 1f / a0
            b0 = b0Raw * invA0
            b1 = b1Raw * invA0
            b2 = b2Raw * invA0
            a1 = a1Raw * invA0
            a2 = a2Raw * invA0
        }

        companion object {
            fun lowPass(sampleRate: Float, frequency: Float, q: Float) = Biquad(sampleRate, frequency, q, Type.LOW_PASS)
            fun highPass(sampleRate: Float, frequency: Float, q: Float) = Biquad(sampleRate, frequency, q, Type.HIGH_PASS)
            fun notch(sampleRate: Float, frequency: Float, q: Float) = Biquad(sampleRate, frequency, q, Type.NOTCH)
        }

        private enum class Type { LOW_PASS, HIGH_PASS, NOTCH }
    }
}
