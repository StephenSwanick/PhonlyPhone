package org.fossify.phone.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.fossify.phone.helpers.MissedCallOverlay

class MissedCallOverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            MissedCallOverlay.refresh(applicationContext)
        }
        return START_STICKY
    }
}
