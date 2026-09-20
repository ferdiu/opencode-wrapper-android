package it.ferdiu.opencodewrapper.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class OpenCodeCarSession : Session() {

    // Plain lazy delegate (not `by lazy`) so the destroy observer can check
    // isInitialized() and only release the TTS engine if it was ever created.
    private val speaker = lazy { CarSpeaker(carContext) }

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY && speaker.isInitialized()) {
                speaker.value.shutdown()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen =
        HomeScreen(carContext, speaker.value)
}
