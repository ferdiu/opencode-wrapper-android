package com.opencode.wrapper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.opencode.wrapper.data.ServerConfigStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val configured = ServerConfigStore(context).get() != null
        if (configured) {
            OpenCodeEventService.start(context)
        }
    }
}
