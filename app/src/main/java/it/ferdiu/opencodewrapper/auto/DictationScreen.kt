package it.ferdiu.opencodewrapper.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import it.ferdiu.opencodewrapper.api.OcSession

/**
 * Captures one voice reply. Permission is requested through the car host
 * ([CarContext.requestPermissions], rendered on the car screen). Live partial
 * results are shown as they arrive; tapping Done (or end-of-speech) moves to
 * confirmation. Every failure ends in a visible, recoverable error state.
 */
class DictationScreen(
    carContext: CarContext,
    private val speaker: CarSpeaker,
    private val session: OcSession,
) : Screen(carContext) {

    private var partial: String = ""
    private var listening = false
    private var error: String? = null
    private var recognizer: SpeechRecognizer? = null

    init {
        // Screen exposes no onDestroy hook (it is a LifecycleOwner only), so
        // the recognizer is torn down from the lifecycle if the screen is
        // popped while still listening. Callbacks arrive on the main thread.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                recognizer?.destroy()
                recognizer = null
            }
        })
        ensurePermissionAndStart()
    }

    private fun ensurePermissionAndStart() {
        when {
            carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startListening()

            // requestPermissions needs host API 5+ (below that the host can't
            // render the permission request at all).
            carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5 ->
                carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                    if (granted.contains(Manifest.permission.RECORD_AUDIO)) startListening()
                    else showError("Microphone permission denied")
                }

            else -> showError("Voice input isn't supported by this car")
        }
    }

    private fun startListening() {
        listening = true
        invalidate()
        val rec = SpeechRecognizer.createSpeechRecognizer(carContext.applicationContext)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let { partial = it; invalidate() }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish(text ?: partial)
            }

            override fun onError(errorCode: Int) {
                if (partial.isNotBlank()) finish(partial) else showError("Couldn't hear you — try again")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
        )
    }

    private fun finish(text: String) {
        recognizer?.destroy()
        recognizer = null
        listening = false
        if (text.isBlank()) showError("Didn't catch anything — try again")
        else screenManager.push(ConfirmScreen(carContext, speaker, session, text))
    }

    private fun showError(message: String) {
        error = message
        listening = false
        invalidate()
    }

    override fun onGetTemplate(): Template {
        error?.let { return errorTemplate(it) }
        return dictationTemplate()
    }

    // ListTemplate.setTitle/setHeaderAction are deprecated in favor of Header
    // (needs host API 8+); kept so the error renders on every host level.
    @Suppress("DEPRECATION")
    private fun errorTemplate(message: String): Template =
        ListTemplate.Builder()
            .setTitle("Reply to ${session.title ?: "session"}")
            .setSingleList(
                ItemList.Builder().addItem(Row.Builder().setTitle(message).build()).build()
            )
            .setHeaderAction(Action.BACK)
            .build()

    // Same deprecated setTitle/setHeaderAction situation as errorTemplate.
    @Suppress("DEPRECATION")
    private fun dictationTemplate(): Template =
        PaneTemplate.Builder(
            Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle(if (listening) "Listening…" else "Processing…")
                        .addText(if (partial.isBlank()) "Speak your reply" else partial)
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("Done")
                        .setOnClickListener { recognizer?.stopListening() }
                        .build()
                )
                .addAction(
                    Action.Builder()
                        .setTitle("Cancel")
                        .setOnClickListener {
                            recognizer?.destroy()
                            recognizer = null
                            screenManager.pop()
                        }
                        .build()
                )
                .build()
        )
            .setTitle("Reply to ${session.title ?: "session"}")
            .setHeaderAction(Action.BACK)
            .build()
}
