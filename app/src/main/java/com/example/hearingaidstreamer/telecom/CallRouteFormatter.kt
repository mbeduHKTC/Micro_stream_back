package com.example.hearingaidstreamer.telecom

import android.content.Context
import android.telecom.CallEndpoint
import com.example.hearingaidstreamer.R

object CallRouteFormatter {
    fun label(context: Context, endpoint: CallEndpoint): String {
        return when (endpoint.endpointType) {
            CallEndpoint.TYPE_BLUETOOTH -> context.getString(R.string.bluetooth_route)
            CallEndpoint.TYPE_SPEAKER -> context.getString(R.string.speaker_route)
            CallEndpoint.TYPE_STREAMING -> context.getString(R.string.hearing_aid_route)
            else -> endpoint.endpointName.toString()
        }
    }
}
