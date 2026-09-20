package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import it.ferdiu.opencodewrapper.api.OcSession

/** Stub replaced by the real session detail in the next task. */
class ReadoutScreen(carContext: CarContext, @Suppress("unused") private val speaker: CarSpeaker, private val session: OcSession) : Screen(carContext) {

    // Same deprecated setTitle/setHeaderAction situation as SessionListScreen.
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template =
        ListTemplate.Builder()
            .setTitle(session.title ?: "Session")
            .setHeaderAction(Action.BACK)
            .setSingleList(
                ItemList.Builder().addItem(Row.Builder().setTitle("Readout lands in the next task").build()).build()
            )
            .build()
}
