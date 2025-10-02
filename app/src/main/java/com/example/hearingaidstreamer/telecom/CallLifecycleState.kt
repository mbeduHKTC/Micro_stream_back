package com.example.hearingaidstreamer.telecom

sealed interface CallLifecycleState {
    data object Idle : CallLifecycleState
    data object Connecting : CallLifecycleState
    data object Active : CallLifecycleState
    data object Ending : CallLifecycleState
    data class Error(val message: String) : CallLifecycleState
}
