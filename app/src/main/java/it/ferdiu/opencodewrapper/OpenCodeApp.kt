package it.ferdiu.opencodewrapper

import android.app.Application
import it.ferdiu.opencodewrapper.notifications.NotificationHelper

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }
}
