package com.opencode.wrapper

import android.app.Application
import com.opencode.wrapper.notifications.NotificationHelper

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }
}
