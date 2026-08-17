package com.opencode.wrapper.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.opencode.wrapper.api.OpenCodeClient
import com.opencode.wrapper.api.OpenCodeClientV2
import com.opencode.wrapper.data.ServerConfig
import com.opencode.wrapper.data.ServerConfigStore
import com.opencode.wrapper.notifications.NotificationHelper
import com.opencode.wrapper.notifications.NotificationRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.collect

/**
 * Keeps a single OpenCode event connection alive for as long as Android will
 * let us, independent of whether MainActivity/WebView is visible or has been
 * suspended by the OS.
 *
 * This is a `specialUse` foreground service (see manifest) rather than
 * `dataSync`, because "maintain an indefinite live connection to a
 * self-hosted dev-tool server" doesn't fit any of the other declared
 * foreground service types and isn't a bounded data-transfer task.
 */
class OpenCodeEventService : LifecycleService() {

    private lateinit var configStore: ServerConfigStore
    private lateinit var notificationRouter: NotificationRouter
    private val reconnectPolicy = ReconnectPolicy()

    private var running = false

    override fun onCreate() {
        super.onCreate()
        configStore = ServerConfigStore(this)
        notificationRouter = NotificationRouter(this)
        NotificationHelper.ensureChannels(this)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val notification = NotificationHelper.buildServiceNotification(
            this, getString(com.opencode.wrapper.R.string.service_notif_connecting)
        )
        ServiceCompat.startForeground(
            this,
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        if (!running) {
            running = true
            startEventLoop()
        }
        return START_STICKY
    }

    private fun startEventLoop() {
        lifecycleScope.launch {
            while (true) {
                val config = configStore.get()
                if (config == null) {
                    updateServiceNotification(getString(com.opencode.wrapper.R.string.service_notif_offline))
                    delay(5_000.milliseconds)
                    continue
                }

                val client: OpenCodeClient = OpenCodeClientV2(config)

                // Recovery pass: catch up on anything that might have
                // happened while we weren't connected (first loop iteration,
                // or right after a dropped connection).
                runRecoveryPass(client)

                updateServiceNotification(getString(com.opencode.wrapper.R.string.service_notif_title))
                reconnectPolicy.reset()

                val connectionEnded = runEventStream(client, config)
                if (!connectionEnded) return@launch // service was stopped

                val delayMs = reconnectPolicy.nextDelayMs()
                updateServiceNotification(getString(com.opencode.wrapper.R.string.service_notif_reconnecting))
                Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt ${reconnectPolicy.currentAttempt})")
                delay(delayMs.milliseconds)
            }
        }
    }

    /** Returns true if the stream ended, and we should reconnect; false only
     *  if collection was canceled because the service is shutting down. */
    private suspend fun runEventStream(client: OpenCodeClient, config: ServerConfig): Boolean {
        return try {
            client.events(sessionId = null) // instance-wide: all sessions
                .onEach { event -> notificationRouter.onEvent(event) }
                .catch { e -> Log.w(TAG, "Event stream error: ${e.message}") }
                .collect()
            true // flow completed (server closed the stream) -> reconnect
        } catch (e: CancellationException) {
            false // service is being torn down, don't reconnect
        }
    }

    private suspend fun runRecoveryPass(client: OpenCodeClient) {
        withContext(NonCancellable) {
            runCatching {
                val sessions = client.listActiveSessions()
                for (s in sessions) {
                    notificationRouter.onRecoveredStatus(s.id, s.statusType)
                }
            }.onFailure { e -> Log.w(TAG, "Recovery pass failed: ${e.message}") }
        }
    }

    private fun updateServiceNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NotificationHelper.SERVICE_NOTIFICATION_ID, NotificationHelper.buildServiceNotification(this, text))
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OpenCodeEventService"

        fun start(context: Context) {
            val intent = Intent(context, OpenCodeEventService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OpenCodeEventService::class.java))
        }
    }
}
