package com.example.hearingaidstreamer.ui

import android.content.Context
import android.os.Build
import android.telecom.CallEndpoint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.hearingaidstreamer.R
import com.example.hearingaidstreamer.audio.LoopbackAudioEngine
import com.example.hearingaidstreamer.media.StreamMediaSessionController
import com.example.hearingaidstreamer.telecom.CallLifecycleState
import com.example.hearingaidstreamer.telecom.TelecomCallManager
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(private val appContext: Context) : ViewModel() {

    private val audioEngine = LoopbackAudioEngine()

    private val mediaController: StreamMediaSessionController?
    private val callManager: TelecomCallManager?

    private val _gainPosition = MutableStateFlow(0f)
    val gainPosition: StateFlow<Float> = _gainPosition.asStateFlow()

    private val _includeMurmurs = MutableStateFlow(false)
    val includeMurmurs: StateFlow<Boolean> = _includeMurmurs.asStateFlow()

    private val _mainsFrequency = MutableStateFlow(50)
    val mainsFrequency: StateFlow<Int> = _mainsFrequency.asStateFlow()

    private val amplitudeHistory = ArrayDeque<Float>()
    private val _waveform = MutableStateFlow<List<Float>>(emptyList())
    val waveform: StateFlow<List<Float>> = _waveform.asStateFlow()

    private val unsupportedState = MutableStateFlow<CallLifecycleState>(
        CallLifecycleState.Error(appContext.getString(R.string.unsupported_version))
    )

    private val emptyEndpoints = MutableStateFlow<List<CallEndpoint>>(emptyList())
    private val nullEndpoint = MutableStateFlow<CallEndpoint?>(null)

    val callState: StateFlow<CallLifecycleState>
    val availableEndpoints: StateFlow<List<CallEndpoint>>
    val activeEndpoint: StateFlow<CallEndpoint?>

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var createdManager: TelecomCallManager? = null
            val controller = StreamMediaSessionController(appContext, viewModelScope, audioEngine) {
                createdManager?.stopCall()
            }
            val manager = TelecomCallManager(appContext, viewModelScope, controller)
            createdManager = manager
            mediaController = controller
            callManager = manager
        } else {
            mediaController = null
            callManager = null
        }

        callState = callManager?.state ?: unsupportedState
        availableEndpoints = callManager?.availableEndpoints ?: emptyEndpoints
        activeEndpoint = callManager?.activeEndpoint ?: nullEndpoint

        // Apply initial settings to the audio engine
        audioEngine.updateSettings { current ->
            current.copy(
                includeMurmurs = _includeMurmurs.value,
                mainsFrequencyHz = _mainsFrequency.value,
                gainMultiplier = sliderToGain(_gainPosition.value)
            )
        }

        viewModelScope.launch {
            audioEngine.envelopeFlow.collect { value ->
                appendAmplitude(value)
            }
        }
    }

    fun startCall() {
        callManager?.startCall()
    }

    fun stopCall() {
        callManager?.stopCall()
    }

    fun requestEndpoint(endpoint: CallEndpoint) {
        callManager?.requestEndpointChange(endpoint)
    }

    fun onGainPositionChanged(position: Float) {
        _gainPosition.value = position
        audioEngine.updateSettings { current -> current.copy(gainMultiplier = sliderToGain(position)) }
    }

    fun onIncludeMurmursChanged(enabled: Boolean) {
        _includeMurmurs.value = enabled
        audioEngine.updateSettings { current -> current.copy(includeMurmurs = enabled) }
    }

    fun onMainsFrequencyChanged(frequency: Int) {
        _mainsFrequency.value = frequency
        audioEngine.updateSettings { current -> current.copy(mainsFrequencyHz = frequency) }
    }

    override fun onCleared() {
        callManager?.stopCall()
        mediaController?.let { controller ->
            viewModelScope.launch { controller.release() }
        }
        super.onCleared()
    }

    private fun appendAmplitude(sample: Float) {
        val capped = sample.coerceIn(0f, 1f)
        amplitudeHistory.addLast(capped)
        while (amplitudeHistory.size > HISTORY_CAPACITY) {
            amplitudeHistory.removeFirst()
        }
        _waveform.value = amplitudeHistory.toList()
    }

    companion object {
        private const val HISTORY_SECONDS = 60
        private const val SAMPLES_PER_SECOND = 25
        private const val HISTORY_CAPACITY = HISTORY_SECONDS * SAMPLES_PER_SECOND

        fun sliderToGain(position: Float): Float {
            val clamped = position.coerceIn(0f, 1f)
            return exp(ln(50f) * clamped)
        }

        fun gainLabel(position: Float): String = String.format(Locale.US, "×%.1f", sliderToGain(position))

        fun factory(appContext: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MainViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(appContext.applicationContext) as T
            }
        }
    }
}
