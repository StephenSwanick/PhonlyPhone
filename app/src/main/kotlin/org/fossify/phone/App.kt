package org.fossify.phone

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import org.fossify.commons.FossifyApp
import org.fossify.phone.helpers.ContactsSyncJob
import org.fossify.phone.helpers.ContactsSyncScheduler
import org.fossify.phone.helpers.ContactsSyncTriggerReceiver
import org.fossify.phone.helpers.DeviceNotificationCue

class App : FossifyApp() {
    private val restrictionsReceiver = ContactsSyncTriggerReceiver()

    override fun onCreate() {
        super.onCreate()
        DeviceNotificationCue.onProcessStart(this)
        registerRestrictionsReceiver()
        ContactsSyncJob.ensure(this)
        ContactsSyncScheduler.request(this)
    }

    /**
     * AOSP sends APPLICATION_RESTRICTIONS_CHANGED with FLAG_RECEIVER_REGISTERED_ONLY,
     * so a later Send to phone only reaches this process if it is already up.
     */
    private fun registerRestrictionsReceiver() {
        val filter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(restrictionsReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(restrictionsReceiver, filter)
        }
    }
}
