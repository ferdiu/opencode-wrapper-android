package it.ferdiu.opencodewrapper.auto

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import it.ferdiu.opencodewrapper.R
import it.ferdiu.opencodewrapper.api.OcSession
import kotlinx.coroutines.launch

/** Root screen: loads all sessions once on entry, then offers Sessions /
 *  Projects as tabs on hosts with car API 8+ (TabTemplate) or as a two-entry
 *  menu on older hosts. Both paths lead to the same screens. Busy sessions
 *  carry a static "Running" marker and sort to the top. */
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

    init {
        lifecycleScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val client = carContext.openCodeClientOrNull()
        state = when {
            client == null -> State.Error("OpenCode server not configured on the phone")
            else -> runCatching {
                // Verified live: unscoped /session only sees the default
                // instance - enumerate projects and fetch per worktree.
                val projects = client.listProjects()
                val colorByWorktree = projects.associate { it.worktree to it.iconColor }
                projects.flatMap { project ->
                    val sessions = runCatching { client.listSessions(directory = project.worktree) }
                        .getOrDefault(emptyList())
                    val statuses = runCatching { client.listSessionStatuses(project.worktree) }
                        .getOrDefault(emptyMap())
                    sessions.map { s ->
                        s.copy(
                            iconColor = colorByWorktree[s.directory],
                            busy = statuses[s.id] in BUSY_STATUS_TYPES,
                        )
                    }
                    // Running sessions first (what you need while driving),
                    // then newest first within each group.
                }.sortedWith(compareByDescending<OcSession> { it.busy }.thenByDescending { it.updatedAt })
            }.fold(
                onSuccess = { State.Ready(it) },
                onFailure = { State.Error("Couldn't reach the OpenCode server") },
            )
        }
        invalidate()
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
            .addTab(newTab(TAB_SESSIONS, "Sessions", R.drawable.ic_tab_sessions))
            .addTab(newTab(TAB_PROJECTS, "Projects", R.drawable.ic_tab_projects))
            .setActiveTabContentId(activeTabContentId)
            .setTabContents(TabContents.Builder(contentTemplate).build())
            // TabTemplate.build() requires an app-icon header action when not loading.
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun newTab(contentId: String, title: String, @DrawableRes iconRes: Int): Tab =
        Tab.Builder()
            .setContentId(contentId)
            .setTitle(title)
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, iconRes)).build())
            .build()

    /** Secondary text of a session row: the project label, prefixed with a
     *  static running marker while busy. RowDecoration/setEndImage don't
     *  exist in car-app 1.7.0 (setEndImage is 1.8.0+), so the marker lives in
     *  the text. */
    private fun sessionRowText(session: OcSession): String {
        val text = session.projectLabel ?: ""
        val decorated = if (session.busy) "▶ Running · $text" else text
        return decorated.trimEnd()
    }

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
                        .setImage(
                            ProjectIconFactory.projectIcon(
                                carContext, session.projectLabel ?: "?", session.iconColor,
                            ),
                            Row.IMAGE_TYPE_LARGE, // LARGE: hosts tint TYPE_ICON images (bitmap badges would render as white silhouettes)
                        )
                        .addText(sessionRowText(session))
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
                        .setImage(
                            ProjectIconFactory.projectIcon(carContext, project.label, project.iconColor),
                            Row.IMAGE_TYPE_LARGE, // LARGE: hosts tint TYPE_ICON images (bitmap badges would render as white silhouettes)
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
        return ListTemplate.Builder().setTitle("Projects").setSingleList(list.build()).build()
    }

    companion object {
        /** TabTemplate requires host car API 8+. */
        private const val MIN_TAB_API_LEVEL = 8
        private const val TAB_SESSIONS = "sessions"
        private const val TAB_PROJECTS = "projects"
        private const val MAX_ROWS = 20
        /** Session status types that count as "running" for the marker. */
        private val BUSY_STATUS_TYPES = setOf("busy", "retry")
    }
}
