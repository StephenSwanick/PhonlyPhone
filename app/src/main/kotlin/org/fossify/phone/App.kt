package org.fossify.phone

import org.fossify.commons.FossifyApp
import org.fossify.phone.helpers.DeviceNotificationCue

class App : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        DeviceNotificationCue.onProcessStart(this)
    }
}
