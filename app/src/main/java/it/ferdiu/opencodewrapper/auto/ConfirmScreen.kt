package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import it.ferdiu.opencodewrapper.api.OcSession

/** Stub replaced by the real confirmation flow in the next task. */
class ConfirmScreen(
    carContext: CarContext,
    @Suppress("unused") private val speaker: CarSpeaker,
    private val session: OcSession,
    private val dictatedText: String,
) : Screen(carContext) {

    // ListTemplate.setTitle/setHeaderAction are deprecated in favor of Header
    // (needs host API 8+); kept so this stub renders on every host level.
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template =
        ListTemplate.Builder()
            .setTitle("Reply to ${session.title ?: "session"}")
            .setHeaderAction(Action.BACK)
            .setSingleList(
                ItemList.Builder().addItem(Row.Builder().setTitle(dictatedText).build()).build()
            )
            .build()
}
