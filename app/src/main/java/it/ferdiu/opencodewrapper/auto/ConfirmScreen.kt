package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import it.ferdiu.opencodewrapper.api.OcSession
import kotlinx.coroutines.launch

/**
 * Reads the dictated reply back (and shows it), then waits for an explicit
 * tap: Send ships it to the session, Retry records again, Cancel drops it.
 * Nothing is ever sent without this confirmation step.
 *
 * Actions are clickable list rows rather than pane actions: hosts cap pane
 * actions (2 on the DHU) and this screen needs three.
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

    // ListTemplate.setTitle/setHeaderAction are deprecated in favor of Header
    // (needs host API 8+); kept so the confirmation renders on every host level.
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("Your reply").addText(dictatedText).build())
            .apply {
                error?.let { addItem(Row.Builder().setTitle(it).build()) }
            }
            .addItem(
                Row.Builder()
                    .setTitle(if (sending) "Sending…" else "Send")
                    .setOnClickListener { send() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Retry")
                    .addText("Record again")
                    .setOnClickListener {
                        screenManager.push(DictationScreen(carContext, speaker, session))
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Cancel")
                    .addText("Discard this reply")
                    .setOnClickListener { screenManager.popToRoot() }
                    .build()
            )
            .build()
        return ListTemplate.Builder()
            .setTitle("Reply to ${session.title ?: "session"}")
            .setHeaderAction(Action.BACK)
            .setSingleList(list)
            .build()
    }
}
