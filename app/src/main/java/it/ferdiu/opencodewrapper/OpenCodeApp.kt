package it.ferdiu.opencodewrapper

import android.app.Application
import com.google.android.material.color.DynamicColors
import it.ferdiu.opencodewrapper.notifications.NotificationHelper

class OpenCodeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: follow the user's wallpaper-based accent palette on
        // Android 12+; older devices fall back to the static light/dark palette.
        DynamicColors.applyToActivitiesIfAvailable(this)
        NotificationHelper.ensureChannels(this)
    }
}
