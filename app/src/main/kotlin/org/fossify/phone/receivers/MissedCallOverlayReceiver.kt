package org.fossify.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.helpers.MissedCallOverlay

class MissedCallOverlayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        ensureBackgroundThread {
            MissedCallOverlay.refresh(context.applicationContext)
            pendingResult.finish()
        }
    }
}
