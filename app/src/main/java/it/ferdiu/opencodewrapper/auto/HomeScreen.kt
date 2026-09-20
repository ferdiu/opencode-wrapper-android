package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import it.ferdiu.opencodewrapper.api.OcSession
import kotlinx.coroutines.launch

/** Root screen: loads all sessions once, then offers Sessions / Projects as
 *  tabs on hosts with car API 8+ (TabTemplate) or as a two-entry menu on
 *  older hosts. Both paths lead to the same screens. */
class HomeScreen(carContext: CarContext, private val speaker: CarSpeaker) : Screen(carContext) {

    private sealed interface State {
        data object Loading : State
        data class Ready(val sessions: List<OcSession>) : State
        data class Error(val message: String) : State
    }

    private var state: State = State.Loading

    /** Active tab content id of the TabTemplate path; mirrored from [tabCallback]. */
    private var activeTabContentId: String = TAB_SESSIONS

    private val tabCallback = object : TabTemplate.TabCallback {
        override fun onTabSelected(contentId: String) {
            activeTabContentId = contentId
            invalidate()
        }
    }

    init { refresh() }

    private fun refresh() {
        lifecycleScope.launch {
            val client = carContext.openCodeClientOrNull()
            state = when {
                client == null -> State.Error("OpenCode server not configured on the phone")
                else -> runCatching {
                    // Verified live: unscoped /session only sees the default
                    // instance - enumerate projects and fetch per worktree.
                    client.listProjects().flatMap { project ->
                        runCatching { client.listSessions(directory = project.worktree) }
                            .getOrDefault(emptyList())
                    }.sortedByDescending { it.updatedAt }
                }.fold(
                    onSuccess = { State.Ready(it) },
                    onFailure = { State.Error("Couldn't reach the OpenCode server") },
                )
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template = when (val current = state) {
        State.Loading -> loadingTemplate()
        is State.Error -> LongMessageTemplate.Builder(current.message)
            .setTitle("OpenCode")
            .build()
        is State.Ready -> if (carContext.carAppApiLevel >= MIN_TAB_API_LEVEL) {
            tabsTemplate(current.sessions)
        } else {
            menuTemplate(current.sessions)
        }
    }

    // ListTemplate.setTitle is deprecated in favor of Header (needs host API
    // 8+); kept so the loading screen renders on every host level.
    @Suppress("DEPRECATION")
    private fun loadingTemplate(): Template =
        ListTemplate.Builder().setTitle("OpenCode").setLoading(true).build()

    // Same deprecated setTitle situation as loadingTemplate.
    @Suppress("DEPRECATION")
    private fun menuTemplate(sessions: List<OcSession>): Template {
        val projects = ProjectGrouper.group(sessions)
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("Sessions")
                    .addText("All sessions, newest first")
                    .setOnClickListener {
                        screenManager.push(SessionListScreen(carContext, speaker, sessions, "Sessions"))
                    }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("Projects")
                    .addText("${projects.size} projects")
                    .setOnClickListener {
                        screenManager.push(ProjectListScreen(carContext, speaker, projects))
                    }
                    .build()
            )
            .build()
        return ListTemplate.Builder().setTitle("OpenCode").setSingleList(list).build()
    }

    // 1.7.0 exposes addTab() rather than the newer setTabs(); both tabs are
    // added individually. Tab itself is not deprecated.
    private fun tabsTemplate(sessions: List<OcSession>): Template {
        val contentTemplate = if (activeTabContentId == TAB_PROJECTS) {
            projectRowsTemplate(sessions)
        } else {
            sessionRowsTemplate(sessions)
        }
        return TabTemplate.Builder(tabCallback)
            .addTab(newTab(TAB_SESSIONS, "Sessions"))
            .addTab(newTab(TAB_PROJECTS, "Projects"))
            .setActiveTabContentId(activeTabContentId)
            .setTabContents(TabContents.Builder(contentTemplate).build())
            .build()
    }

    private fun newTab(contentId: String, title: String): Tab =
        Tab.Builder().setContentId(contentId).setTitle(title).setIcon(CarIcon.APP_ICON).build()

    // Same deprecated setTitle situation as loadingTemplate; rows mirror
    // SessionListScreen so both entry paths render identically.
    @Suppress("DEPRECATION")
    private fun sessionRowsTemplate(sessions: List<OcSession>): Template {
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
        return ListTemplate.Builder().setTitle("Sessions").setSingleList(list.build()).build()
    }

    // Same deprecated setTitle situation as loadingTemplate.
    @Suppress("DEPRECATION")
    private fun projectRowsTemplate(sessions: List<OcSession>): Template {
        val projects = ProjectGrouper.group(sessions)
        val list = ItemList.Builder()
        if (projects.isEmpty()) {
            // ItemList must contain at least one row.
            list.addItem(Row.Builder().setTitle("No projects yet").build())
        } else {
            projects.forEach { project ->
                list.addItem(
                    Row.Builder()
                        .setTitle(project.label)
                        .addText("${project.sessions.size} sessions")
                        .setOnClickListener {
                            screenManager.push(
                                SessionListScreen(carContext, speaker, project.sessions, project.label)
                            )
                        }
                        .build()
                )
            }
        }
        return ListTemplate.Builder().setTitle("Projects").setSingleList(list.build()).build()
    }

    companion object {
        /** TabTemplate requires host car API 8+. */
        private const val MIN_TAB_API_LEVEL = 8
        private const val TAB_SESSIONS = "sessions"
        private const val TAB_PROJECTS = "projects"
        private const val MAX_ROWS = 20
    }
}
