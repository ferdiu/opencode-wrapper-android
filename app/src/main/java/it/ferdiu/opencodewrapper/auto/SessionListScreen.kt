package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import it.ferdiu.opencodewrapper.api.OcSession

/** Rows of sessions: title = session title, subtitle = project name. Used for
 *  the global list and, pre-filtered, inside a project. Hosts may cap row
 *  count while driving; we show at most [MAX_ROWS]. */
class SessionListScreen(
    carContext: CarContext,
    private val speaker: CarSpeaker,
    private val sessions: List<OcSession>,
    private val title: String,
) : Screen(carContext) {

    // ListTemplate.setTitle/setHeaderAction are deprecated in favor of Header
    // (needs host API 8+); kept so this list renders on every host level.
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (sessions.isEmpty()) {
            list.addItem(Row.Builder().setTitle("No sessions yet").addText("Start one from your terminal").build())
        } else {
            sessions.take(MAX_ROWS).forEach { session ->
                list.addItem(
                    Row.Builder()
                        .setTitle(session.title ?: "Untitled session")
                        .addText(session.projectLabel ?: "")
                        .setOnClickListener {
                            screenManager.push(ReadoutScreen(carContext, speaker, session))
                        }
                        .build()
                )
            }
        }
        return ListTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    companion object {
        private const val MAX_ROWS = 20
    }
}
