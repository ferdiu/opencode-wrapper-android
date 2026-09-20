package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import it.ferdiu.opencodewrapper.api.OcSession
import kotlinx.coroutines.launch

/**
 * Reads the dictated reply back (and shows it), then waits for an explicit
 * tap: Send ships it to the session, Retry records again, Cancel drops it.
 * Nothing is ever sent without this confirmation step.
 */
class ConfirmScreen(
    carContext: CarContext,
    private val speaker: CarSpeaker,
    private val session: OcSession,
    private val dictatedText: String,
) : Screen(carContext) {

    private var sending = false
    private var error: String? = null

    init {
        lifecycleScope.launch { speaker.speakAndWait("You said: $dictatedText") }
    }

    private fun send() {
        if (sending) return
        sending = true
        error = null
        invalidate()
        lifecycleScope.launch {
            val client = carContext.openCodeClientOrNull()
            val ok = client != null &&
                runCatching { client.sendPrompt(session.id, dictatedText, session.directory) }.getOrDefault(false)
            if (ok) {
                speaker.speakAndWait("Message sent")
                screenManager.popToRoot()
            } else {
                error = "Couldn't send the message"
                sending = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val paneBuilder = Pane.Builder()
            .addRow(Row.Builder().setTitle("Your reply").addText(dictatedText).build())
        error?.let { paneBuilder.addRow(Row.Builder().setTitle(it).build()) }
        val pane = paneBuilder
            .addAction(
                Action.Builder()
                    .setTitle(if (sending) "Sending…" else "Send")
                    .setOnClickListener { send() }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Retry")
                    .setOnClickListener {
                        screenManager.push(DictationScreen(carContext, speaker, session))
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Cancel")
                    .setOnClickListener { screenManager.popToRoot() }
                    .build()
            )
            .build()
        return confirmTemplate(pane)
    }

    // PaneTemplate.setTitle/setHeaderAction are deprecated in favor of Header
    // (needs host API 8+); kept so the confirmation renders on every host level.
    @Suppress("DEPRECATION")
    private fun confirmTemplate(pane: Pane): Template =
        PaneTemplate.Builder(pane)
            .setTitle("Reply to ${session.title ?: "session"}")
            .setHeaderAction(Action.BACK)
            .build()
}
