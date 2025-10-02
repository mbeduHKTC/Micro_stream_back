package com.example.hearingaidstreamer.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.OutcomeReceiver
import android.telecom.CallAttributes
import android.telecom.CallControl
import android.telecom.CallControlCallback
import android.telecom.CallEndpoint
import android.telecom.CallEventCallback
import android.telecom.CallException

import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.hearingaidstreamer.R
import com.example.hearingaidstreamer.media.StreamMediaSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Coordinates a self-managed VoIP call using the platform Telecom APIs (Android 14+).
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class TelecomCallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mediaController: StreamMediaSessionController
) {

    private val telecomManager: TelecomManager =
        context.getSystemService(TelecomManager::class.java)
            ?: throw IllegalStateException("TelecomManager not available")

    private val phoneAccountHandle = PhoneAccountHandle(
        ComponentName(context, HearingAidConnectionService::class.java),
        PHONE_ACCOUNT_ID
    )

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private val _state = MutableStateFlow<CallLifecycleState>(CallLifecycleState.Idle)
    val state: StateFlow<CallLifecycleState> = _state

    private val _availableEndpoints = MutableStateFlow<List<CallEndpoint>>(emptyList())
    val availableEndpoints: StateFlow<List<CallEndpoint>> = _availableEndpoints

    private val _activeEndpoint = MutableStateFlow<CallEndpoint?>(null)
    val activeEndpoint: StateFlow<CallEndpoint?> = _activeEndpoint

    private var callControl: CallControl? = null

    private val controlCallback = object : CallControlCallback {
        override fun onSetActive(wasCompleted: Consumer<Boolean>) {
            scope.launch { activateCall() }
            wasCompleted.accept(true)
        }

        override fun onSetInactive(wasCompleted: Consumer<Boolean>) {
            scope.launch { mediaController.pause() }
            _state.value = CallLifecycleState.Ending
            wasCompleted.accept(true)
        }

        override fun onAnswer(videoState: Int, wasCompleted: Consumer<Boolean>) {
            scope.launch { activateCall() }
            wasCompleted.accept(true)
        }

        override fun onDisconnect(disconnectCause: DisconnectCause, wasCompleted: Consumer<Boolean>) {
            scope.launch {
                stopCallInternal(disconnectCause)
                wasCompleted.accept(true)
            }
        }

        override fun onCallStreamingStarted(wasCompleted: Consumer<Boolean>) {
            // App handles streaming internally; acknowledge request so Telecom can continue.
            wasCompleted.accept(true)
        }
    }


    private val eventCallback = object : CallEventCallback {
        override fun onCallEndpointChanged(newCallEndpoint: CallEndpoint) {
            _activeEndpoint.value = newCallEndpoint
        }

        override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
            _availableEndpoints.value = availableEndpoints
        }

        override fun onMuteStateChanged(isMuted: Boolean) {
            // Hook for future mute UI; no-op for now.
        }

        override fun onCallStreamingFailed(reason: Int) {
            _state.value = CallLifecycleState.Error(
                context.getString(R.string.call_streaming_failed_generic)
            )
        }

        override fun onEvent(event: String, extras: android.os.Bundle) {
            // Reserved for companion surfaces (car, wearable, etc.)
        }
    }

    init {
        ensurePhoneAccountRegistered()
    }

    fun startCall() {
        if (callControl != null) return
        _state.value = CallLifecycleState.Connecting

        val handle = Uri.fromParts("voip", "hearing_stream", null)
        val callAttributes = CallAttributes.Builder(
            phoneAccountHandle,
            CallAttributes.DIRECTION_OUTGOING,
            context.getString(R.string.app_name),
            handle
        )
            .setCallType(CallAttributes.AUDIO_CALL)
            .setCallCapabilities(CallAttributes.SUPPORTS_SET_INACTIVE or CallAttributes.SUPPORTS_STREAM)
            .build()

        telecomManager.addCall(
            callAttributes,
            mainExecutor,
            object : OutcomeReceiver<CallControl, CallException> {
                override fun onResult(result: CallControl) {
                    callControl = result
                    requestActive(result)
                }

                override fun onError(error: CallException) {
                    _state.value = CallLifecycleState.Error(error.message ?: "Call setup failed")
                }
            },
            controlCallback,
            eventCallback
        )
    }

    fun stopCall() {
        val control = callControl ?: return
        val disconnectCause = DisconnectCause(DisconnectCause.LOCAL)
        control.disconnect(disconnectCause, mainExecutor, outcomeReceiver("disconnect"))
        scope.launch { stopCallInternal(disconnectCause) }
    }

    fun requestEndpointChange(endpoint: CallEndpoint) {
        val control = callControl ?: return
        control.requestCallEndpointChange(endpoint, mainExecutor, outcomeReceiver("endpoint"))
    }

    private fun requestActive(control: CallControl) {
        control.setActive(mainExecutor, outcomeReceiver("setActive") { success ->
            if (success) {
                scope.launch { activateCall() }
            } else {
                _state.value = CallLifecycleState.Error(context.getString(R.string.call_activation_failed))
            }
        })
    }

    private suspend fun stopCallInternal(disconnectCause: DisconnectCause) {
        mediaController.stop()
        withContext(Dispatchers.Main) {
            _state.value = CallLifecycleState.Idle
            _availableEndpoints.value = emptyList()
            _activeEndpoint.value = null
            callControl = null
        }
    }

    private suspend fun activateCall() {
        mediaController.play()
        withContext(Dispatchers.Main) {
            _state.value = CallLifecycleState.Active
        }
    }

    private fun outcomeReceiver(
        tag: String,
        onResult: ((Boolean) -> Unit)? = null
    ): OutcomeReceiver<Void, CallException> {
        return object : OutcomeReceiver<Void, CallException> {
            override fun onResult(result: Void?) {
                onResult?.invoke(true)
            }

            override fun onError(error: CallException) {
                Log.e(TAG, "$tag failed", error)
                onResult?.invoke(false)
            }
        }
    }

    private fun ensurePhoneAccountRegistered() {
        val hasAccount = try {
            telecomManager.getPhoneAccount(phoneAccountHandle) != null
        } catch (_: SecurityException) {
            false // No permission; just try to register
        }
        if (hasAccount) return

        val phoneAccount = PhoneAccount.Builder(
            phoneAccountHandle,
            context.getString(R.string.app_name)
        )
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setShortDescription(context.getString(R.string.app_name))
            .setSupportedUriSchemes(listOf("voip", PhoneAccount.SCHEME_SIP))
            .build()

        telecomManager.registerPhoneAccount(phoneAccount)
    }


    companion object {
        private const val TAG = "TelecomCallManager"
        private const val PHONE_ACCOUNT_ID = "hearing_aid_streamer_account"
    }
}
