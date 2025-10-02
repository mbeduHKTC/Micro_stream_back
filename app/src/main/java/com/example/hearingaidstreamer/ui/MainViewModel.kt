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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(private val appContext: Context) : ViewModel() {

    private val audioEngine = LoopbackAudioEngine()

    private val mediaController: StreamMediaSessionController?
    private val callManager: TelecomCallManager?

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
    }

    private val unsupportedState = MutableStateFlow<CallLifecycleState>(
        CallLifecycleState.Error(appContext.getString(R.string.unsupported_version))
    )

    private val emptyEndpoints = MutableStateFlow<List<CallEndpoint>>(emptyList())
    private val nullEndpoint = MutableStateFlow<CallEndpoint?>(null)

    val callState: StateFlow<CallLifecycleState> = callManager?.state ?: unsupportedState
    val availableEndpoints: StateFlow<List<CallEndpoint>> = callManager?.availableEndpoints ?: emptyEndpoints
    val activeEndpoint: StateFlow<CallEndpoint?> = callManager?.activeEndpoint ?: nullEndpoint

    fun startCall() {
        callManager?.startCall()
    }

    fun stopCall() {
        callManager?.stopCall()
    }

    fun requestEndpoint(endpoint: CallEndpoint) {
        callManager?.requestEndpointChange(endpoint)
    }

    override fun onCleared() {
        callManager?.stopCall()
        mediaController?.let { controller ->
            viewModelScope.launch { controller.release() }
        }
        super.onCleared()
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MainViewModel::class.java))
                return MainViewModel(appContext.applicationContext) as T
            }
        }
    }
}
