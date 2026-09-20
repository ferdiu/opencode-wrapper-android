package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Template

/** Parked-only full-text view of a single message; opened from the message
 *  row on [ReadoutScreen]. LongMessageTemplate is restricted to parked
 *  hosts, which is exactly the intent. */
class FullTextScreen(
    carContext: CarContext,
    private val author: String,
    private val text: String,
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        LongMessageTemplate.Builder(text)
            .setTitle(author)
            .setHeaderAction(Action.BACK)
            .build()
}
