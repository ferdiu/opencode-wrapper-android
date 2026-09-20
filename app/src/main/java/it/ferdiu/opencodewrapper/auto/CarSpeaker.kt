package it.ferdiu.opencodewrapper.auto

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thin suspend-friendly wrapper over [TextToSpeech] for the car screens.
 * Uses USAGE_ASSISTANT so read-aloud ducks media like the car's own
 * assistant would. One instance per [OpenCodeCarSession].
 *
 * Utterance completions are routed through ONE persistent
 * [UtteranceProgressListener] (installed after init succeeds) keyed by
 * utterance id, and calls are serialized with a [Mutex]: a second
 * concurrent [speakAndWait] would otherwise replace the first call's
 * listener and leave its coroutine awaiting forever.
 */
class CarSpeaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) installProgressListener()
        ready.complete(status == TextToSpeech.SUCCESS)
    }
    private val utteranceCounter = AtomicInteger(0)
    private val speakMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

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
        return speakMutex.withLock {
            val utteranceId = "oc-${utteranceCounter.incrementAndGet()}"
            val done = CompletableDeferred<Boolean>()
            pending[utteranceId] = done
            try {
                val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
                // QUEUE_FLUSH is safe: concurrent calls are serialized above.
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
                done.await()
            } finally {
                pending.remove(utteranceId)
            }
        }
    }

    fun shutdown() {
        // Fail any in-flight speak so its caller doesn't hang on a TTS that
        // will never deliver another callback.
        pending.values.forEach { it.complete(false) }
        pending.clear()
        tts.stop()
        tts.shutdown()
    }

    private fun installProgressListener() {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id != null) pending.remove(id)?.complete(true) }
            @Suppress("OVERRIDE_DEPRECATION") // onError(utteranceId) is the pre-API-21 signature we must override
            @Deprecated("deprecated in Java")
            override fun onError(id: String?) { if (id != null) pending.remove(id)?.complete(false) }
        })
    }
}
