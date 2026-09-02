package org.fossify.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.phone.helpers.DeviceNotificationApi
import org.fossify.phone.helpers.DeviceNotificationCue

class DeviceNotificationCueReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val source = intent.getStringExtra(DeviceNotificationCue.EXTRA_SOURCE).orEmpty()
        if (source == DeviceNotificationApi.SOURCE) {
            return
        }
        when (action) {
            DeviceNotificationCue.ACTION_LOOKED -> {
                DeviceNotificationCue.onPeerLooked(context.applicationContext)
            }
            DeviceNotificationCue.ACTION_LEFT -> {
                DeviceNotificationCue.onPeerLeft(context.applicationContext)
            }
        }
    }
}
