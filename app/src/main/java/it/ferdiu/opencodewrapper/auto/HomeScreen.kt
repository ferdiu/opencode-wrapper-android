package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/** Stub replaced by the real navigation in the next task. */
class HomeScreen(carContext: CarContext, @Suppress("unused") private val speaker: CarSpeaker) : Screen(carContext) {

    // TODO(task 7): replaced by Header-based template; setTitle is deprecated but kept here
    // because Header requires a newer host API level than this stub targets.
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("Car connection works").addText("Navigation lands in the next task").build())
            .build()
        return ListTemplate.Builder().setTitle("OpenCode").setSingleList(list).build()
    }
}
