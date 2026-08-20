package org.fossify.phone.helpers

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import org.fossify.phone.extensions.getStateCompat
import org.fossify.phone.extensions.isOutgoing
import java.util.Collections
import java.util.WeakHashMap

/**
 * Complete a blocked incoming call so the carrier usually skips voicemail.
 * Does not go through [CallManager], so the in-call UI is not opened.
 * Emergency numbers are allowed in [CallAllowlist] and never reach here.
 */
object IncomingAllowlistDrop {
    private val dropping = Collections.newSetFromMap(WeakHashMap<Call, Boolean>())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun isIncomingBlocked(service: InCallService, call: Call): Boolean {
        if (call.isOutgoing()) {
            return false
        }
        val number = call.details?.handle?.schemeSpecificPart
        return !CallAllowlist.isNumberAllowed(service, number)
    }

    fun start(service: InCallService, call: Call) {
        dropping.add(call)

        // Do not answer a second line while an allowed call is live.
        if (CallManager.getPhoneState() != NoCall) {
            rejectWithoutAnswering(call)
            return
        }

        service.setMuted(true)

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                advance(service, call, this)
            }
        }
        call.registerCallback(callback, mainHandler)
        mainHandler.post { advance(service, call, callback) }
    }

    fun onRemoved(call: Call): Boolean {
        return dropping.remove(call)
    }

    private fun rejectWithoutAnswering(call: Call) {
        when (call.getStateCompat()) {
            Call.STATE_RINGING -> call.reject(false, null)
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> Unit
            else -> call.disconnect()
        }
    }

    private fun advance(service: InCallService, call: Call, callback: Call.Callback) {
        when (call.getStateCompat()) {
            Call.STATE_RINGING -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
            Call.STATE_ACTIVE, Call.STATE_HOLDING -> call.disconnect()
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                call.unregisterCallback(callback)
                service.setMuted(false)
            }
        }
    }
}
