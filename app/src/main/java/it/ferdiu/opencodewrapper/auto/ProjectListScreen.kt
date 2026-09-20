package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

/** Rows of projects (label + session count); tapping drills into that
 *  project's sessions. Reached from the HomeScreen menu fallback path. */
class ProjectListScreen(
    carContext: CarContext,
    private val speaker: CarSpeaker,
    private val projects: List<ProjectGroup>,
) : Screen(carContext) {

    // Same deprecated setTitle/setHeaderAction situation as SessionListScreen.
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (projects.isEmpty()) {
            // ItemList must contain at least one row; a reachable empty
            // projects list would otherwise crash at build() time.
            list.addItem(Row.Builder().setTitle("No projects yet").build())
        } else {
            projects.forEach { project ->
                list.addItem(
                    Row.Builder()
                        .setTitle(project.label)
                        .setImage(
                            ProjectIconFactory.projectIcon(carContext, project.label, project.iconColor),
                            Row.IMAGE_TYPE_ICON,
                        )
                        .addText("${project.sessions.size} sessions")
                        .setOnClickListener {
                            // Inside a project the rows are all the same
                            // project - no per-row badges needed.
                            screenManager.push(
                                SessionListScreen(
                                    carContext, speaker, project.sessions, project.label,
                                    showProjectIcons = false,
                                )
                            )
                        }
                        .build()
                )
            }
        }
        return ListTemplate.Builder()
            .setTitle("Projects")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
