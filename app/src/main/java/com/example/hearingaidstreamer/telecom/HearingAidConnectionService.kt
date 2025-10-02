package com.example.hearingaidstreamer.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle

class HearingAidConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ): Connection {
        return createStubConnection()
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest
    ): Connection {
        return createStubConnection()
    }

    private fun createStubConnection(): Connection {
        return object : Connection() {
            init {
                setConnectionProperties(PROPERTY_SELF_MANAGED)
                setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
                destroy()
            }
        }
    }
}
