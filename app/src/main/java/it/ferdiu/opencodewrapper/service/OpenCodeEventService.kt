package it.ferdiu.opencodewrapper.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import it.ferdiu.opencodewrapper.api.ApiVersionDetector
import it.ferdiu.opencodewrapper.api.ApiVersionException
import it.ferdiu.opencodewrapper.api.OcEnvelope
import it.ferdiu.opencodewrapper.api.OcEvent
import it.ferdiu.opencodewrapper.api.OpenCodeClient
import it.ferdiu.opencodewrapper.data.ServerConfig
import it.ferdiu.opencodewrapper.data.ServerConfigStore
import it.ferdiu.opencodewrapper.notifications.NotificationHelper
import it.ferdiu.opencodewrapper.notifications.NotificationRouter
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
            this, getString(it.ferdiu.opencodewrapper.R.string.service_notif_connecting)
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
                    updateServiceNotification(getString(it.ferdiu.opencodewrapper.R.string.service_notif_offline))
                    delay(5_000.milliseconds)
                    continue
                }

                // Detection is cached per base URL, so reconnects don't re-probe.
                val client: OpenCodeClient = try {
                    ApiVersionDetector.clientFor(config)
                } catch (e: ApiVersionException) {
                    // Strict-v2 profile on a non-v2 server: surface it and
                    // stop the connection loop for this server instead of
                    // retry-flooding (the loop restarts with the service).
                    Log.w(TAG, "Server rejected: ${e.message}")
                    updateServiceNotification(getString(it.ferdiu.opencodewrapper.R.string.service_notif_not_v2_server))
                    return@launch
                }

                // Recovery pass: catch up on anything that might have
                // happened while we weren't connected (first loop iteration,
                // or right after a dropped connection).
                runRecoveryPass(client)

                updateServiceNotification(getString(it.ferdiu.opencodewrapper.R.string.service_notif_title))
                reconnectPolicy.reset()

                val connectionEnded = runEventStream(client, config)
                if (!connectionEnded) return@launch // service was stopped

                val delayMs = reconnectPolicy.nextDelayMs()
                updateServiceNotification(getString(it.ferdiu.opencodewrapper.R.string.service_notif_reconnecting))
                Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt ${reconnectPolicy.currentAttempt})")
                delay(delayMs.milliseconds)
            }
        }
    }

    /** Returns true if the stream ended, and we should reconnect; false only
     *  if collection was canceled because the service is shutting down. */
    private suspend fun runEventStream(client: OpenCodeClient, config: ServerConfig): Boolean {
        return try {
            client.events() // global stream: all sessions across all projects
                .onEach { envelope -> routeEvent(client, envelope) }
                .catch { e -> Log.w(TAG, "Event stream error", e) }
                .collect()
            true // flow completed (server closed the stream) -> reconnect
        } catch (e: CancellationException) {
            false // service is being torn down, don't reconnect
        }
    }

    /** Feeds one stream event to the notification router, fetching the
     *  session title on demand first when a terminal event (idle/error) is
     *  about to surface a notification for a session whose title was never
     *  learned. OpenCode v2 generates titles in a background fiber, so
     *  `session.renamed` can land AFTER `session.execution.succeeded` - and
     *  sessions that existed before we connected never announce a title at
     *  all. One extra REST call only in the unknown-title case. */
    private suspend fun routeEvent(client: OpenCodeClient, envelope: OcEnvelope) {
        val event = envelope.event
        val sessionId = when (event) {
            is OcEvent.SessionIdle -> event.sessionId
            is OcEvent.SessionError -> event.sessionId
            else -> null
        }
        if (sessionId != null && notificationRouter.needsTitle(sessionId)) {
            val title = runCatching { client.getSessionSnapshot(sessionId)?.title }.getOrNull()
            if (title != null) {
                notificationRouter.onEvent(OcEvent.SessionUpdated(sessionId, title), envelope.directory)
            }
        }
        notificationRouter.onEvent(event, envelope.directory)
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
