package com.example.hearingaidstreamer.telecom

import android.content.Context
import com.example.hearingaidstreamer.R

fun CallLifecycleState.readableStatus(context: Context): String = when (this) {
    CallLifecycleState.Idle -> context.getString(R.string.call_idle)
    CallLifecycleState.Connecting -> context.getString(R.string.call_connecting)
    CallLifecycleState.Active -> context.getString(R.string.call_active)
    CallLifecycleState.Ending -> context.getString(R.string.call_ending)
    is CallLifecycleState.Error -> context.getString(R.string.call_error_prefix, message)
}
