package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import it.ferdiu.opencodewrapper.api.OcSession
import it.ferdiu.opencodewrapper.api.PendingPermission
import it.ferdiu.opencodewrapper.api.PermissionDecision
import it.ferdiu.opencodewrapper.api.SessionMessage
import kotlinx.coroutines.launch

/**
 * Session detail. Shows the last message as TEXT (preview) with explicit
 * actions - nothing is spoken automatically:
 *   Read  → TTS reads the full message aloud
 *   Reply → dictation flow
 * Tapping the message row opens the parked full-text view.
 *
 * When the session has a pending permission request, the screen switches to
 * the permission variant: command text + Read / Allow / Deny / Allow always,
 * answered through the same endpoint as the notification actions.
 */
class ReadoutScreen(
    carContext: CarContext,
    private val speaker: CarSpeaker,
    private val session: OcSession,
) : Screen(carContext) {

    private sealed interface State {
        data object Loading : State
        data class Message(val message: SessionMessage) : State
        data class Permission(val pending: PendingPermission) : State
        data class Error(val message: String) : State
    }

    private var state: State = State.Loading
    private var busy = false

    init {
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val client = carContext.openCodeClientOrNull()
            if (client == null) {
                state = State.Error("OpenCode server not configured on the phone")
                invalidate()
                return@launch
            }
            val pending = runCatching { client.listPendingPermissions(session.id, session.directory) }.getOrDefault(emptyList())
            state = when {
                pending.isNotEmpty() -> State.Permission(pending.first())
                else -> {
                    val result = runCatching { client.getLastMessage(session.id, session.directory) }
                    when {
                        result.isFailure -> State.Error("Couldn't reach the OpenCode server")
                        result.getOrNull() == null -> State.Error("Nothing to show in this session")
                        else -> State.Message(result.getOrNull()!!)
                    }
                }
            }
            invalidate()
        }
    }

    private fun speak(text: String) {
        lifecycleScope.launch {
            val spoken = if (text.length > MAX_SPOKEN_CHARS) text.take(MAX_SPOKEN_CHARS) + "…" else text
            speaker.speakAndWait(spoken)
        }
    }

    private fun decide(pending: PendingPermission, decision: PermissionDecision) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val client = carContext.openCodeClientOrNull()
            val ok = client != null &&
                runCatching { client.replyPermission(session.id, pending.id, decision, session.directory) }.getOrDefault(false)
            busy = false
            if (ok) {
                speaker.speakAndWait("Answer sent")
                load() // refresh: new last message, or the next pending request
            } else {
                state = State.Error("Couldn't send the answer — maybe it was already handled elsewhere")
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template = when (val s = state) {
        State.Loading -> loadingTemplate()

        is State.Error -> errorTemplate(s.message)

        is State.Message -> messageTemplate(s.message)

        is State.Permission -> permissionTemplate(s.pending)
    }

    // PaneTemplate.setTitle/setHeaderAction are deprecated in favor of Header
    // (needs host API 8+); kept so the detail renders on every host level.
    @Suppress("DEPRECATION")
    private fun messageTemplate(message: SessionMessage): Template {
        val who = if (message.isFromUser) "You said" else "Assistant said"
        return PaneTemplate.Builder(
            Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle(who)
                        .addText(message.text)
                        .setOnClickListener {
                            screenManager.push(FullTextScreen(carContext, who, message.text))
                        }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("Read")
                        .setOnClickListener { speak("$who: ${message.text}") }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("Reply")
                        .setOnClickListener {
                            screenManager.push(DictationScreen(carContext, speaker, session))
                        }
                        .build()
                )
                .build()
        )
            .setTitle(session.title ?: "Session")
            .setHeaderAction(Action.BACK)
            .build()
    }

    // Same deprecated setTitle/setHeaderAction situation as messageTemplate.
    // Pane allows at most 4 actions on most hosts - this variant uses exactly
    // 4 (Back lives in the header). That's intentional.
    @Suppress("DEPRECATION")
    private fun permissionTemplate(pending: PendingPermission): Template =
        PaneTemplate.Builder(
            Pane.Builder()
                .addRow(Row.Builder().setTitle("Permission needed").addText(pending.label).build())
                .addAction(Action.Builder().setTitle("Read")
                    .setOnClickListener { speak("Permission needed: ${pending.label}") }.build())
                .addAction(Action.Builder().setTitle("Allow")
                    .setOnClickListener { decide(pending, PermissionDecision.ONCE) }.build())
                .addAction(Action.Builder().setTitle("Deny")
                    .setOnClickListener { decide(pending, PermissionDecision.REJECT) }.build())
                .addAction(Action.Builder().setTitle("Allow always")
                    .setOnClickListener { decide(pending, PermissionDecision.ALWAYS) }.build())
                .build()
        )
            .setTitle(session.title ?: "Session")
            .setHeaderAction(Action.BACK)
            .build()

    // ListTemplate.setTitle/setHeaderAction are deprecated in favor of Header
    // (needs host API 8+); kept so these screens render on every host level.
    @Suppress("DEPRECATION")
    private fun loadingTemplate(): Template =
        ListTemplate.Builder()
            .setTitle(session.title ?: "Session")
            .setLoading(true)
            .build()

    // Same deprecated setTitle/setHeaderAction situation as loadingTemplate.
    @Suppress("DEPRECATION")
    private fun errorTemplate(message: String): Template =
        ListTemplate.Builder()
            .setTitle(session.title ?: "Session")
            .setSingleList(
                ItemList.Builder().addItem(Row.Builder().setTitle(message).build()).build()
            )
            .setHeaderAction(Action.BACK)
            .build()

    companion object {
        /** Long monologues are a distraction; the full text stays on screen. */
        private const val MAX_SPOKEN_CHARS = 800
    }
}
