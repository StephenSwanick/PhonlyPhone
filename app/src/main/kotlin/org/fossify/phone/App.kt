package org.fossify.phone

import org.fossify.commons.FossifyApp
import org.fossify.phone.helpers.MissedCallOverlay

class App : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        MissedCallOverlay.refresh(this)
    }
}
