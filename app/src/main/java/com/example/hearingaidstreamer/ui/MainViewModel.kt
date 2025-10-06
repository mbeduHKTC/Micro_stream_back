package com.example.hearingaidstreamer.ui

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

data class InputDeviceOption(val id: Int, val label: String)

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

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)

    private val _inputDevices = MutableStateFlow<List<InputDeviceOption>>(emptyList())
    val inputDevices: StateFlow<List<InputDeviceOption>> = _inputDevices.asStateFlow()

    private val _selectedInputDeviceId = MutableStateFlow<Int?>(null)
    val selectedInputDeviceId: StateFlow<Int?> = _selectedInputDeviceId.asStateFlow()

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
            val controller = StreamMediaSessionController(
                context = appContext,
                scope = viewModelScope,
                loopbackAudioEngine = audioEngine,
                onStopRequested = { createdManager?.stopCall() },
                onMuteStateChanged = { muted -> _isMuted.value = muted }
            )
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

        audioEngine.setPreferredInputDeviceProvider { resolvePreferredInputDevice() }
        refreshInputDevices()
        registerDeviceCallback()

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

    fun onInputDeviceSelected(deviceId: Int?) {
        _selectedInputDeviceId.value = deviceId
        audioEngine.setPreferredInputDeviceProvider { resolvePreferredInputDevice() }
    }

    fun toggleMute() {
        val target = !_isMuted.value
        viewModelScope.launch {
            mediaController?.setMuted(target)
        }
    }

    override fun onCleared() {
        callManager?.stopCall()
        mediaController?.let { controller ->
            viewModelScope.launch { controller.release() }
        }
        unregisterDeviceCallback()
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

    private fun registerDeviceCallback() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        }
    }

    private fun unregisterDeviceCallback() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
    }

    private fun refreshInputDevices() {
        val manager = audioManager ?: return
        val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .map { device ->
                InputDeviceOption(
                    id = device.id,
                    label = formatDeviceLabel(device)
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }

        _inputDevices.value = devices
        val currentId = _selectedInputDeviceId.value
        if (currentId != null && devices.none { it.id == currentId }) {
            _selectedInputDeviceId.value = null
        }
    }

    private fun resolvePreferredInputDevice(): AudioDeviceInfo? {
        val manager = audioManager ?: return null
        val targetId = _selectedInputDeviceId.value ?: return null
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == targetId }
    }

    private fun formatDeviceLabel(device: AudioDeviceInfo): String {
        val productName = device.productName?.toString()?.takeIf { it.isNotBlank() }
        val typeLabel = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> appContext.getString(R.string.input_device_builtin)
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> appContext.getString(R.string.input_device_bluetooth)
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> appContext.getString(R.string.input_device_usb)
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> appContext.getString(R.string.input_device_wired)
            else -> appContext.getString(R.string.input_device_other)
        }

        return when {
            productName == null -> typeLabel
            productName.equals(typeLabel, ignoreCase = true) -> productName
            else -> appContext.getString(R.string.input_device_combined_label, productName, typeLabel)
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshInputDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshInputDevices()
        }
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
