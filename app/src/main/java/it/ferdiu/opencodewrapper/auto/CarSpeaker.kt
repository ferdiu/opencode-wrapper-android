package it.ferdiu.opencodewrapper.auto

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin suspend-friendly wrapper over [TextToSpeech] for the car screens.
 * Uses USAGE_ASSISTANT so read-aloud ducks media like the car's own
 * assistant would. One instance per [OpenCodeCarSession].
 */
class CarSpeaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    private val utteranceCounter = AtomicInteger(0)

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    /** Speaks [text], suspending until playback finishes. Returns false
     *  instead of hanging when TTS is unavailable or fails. */
    suspend fun speakAndWait(text: String): Boolean {
        if (!ready.await()) return false
        val utteranceId = "oc-${utteranceCounter.incrementAndGet()}"
        val done = CompletableDeferred<Boolean>()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == utteranceId) done.complete(true) }
            @Suppress("OVERRIDE_DEPRECATION") // onError(utteranceId) is the pre-API-21 signature we must override
            @Deprecated("deprecated in Java")
            override fun onError(id: String?) { if (id == utteranceId) done.complete(false) }
        })
        val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        return done.await()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
