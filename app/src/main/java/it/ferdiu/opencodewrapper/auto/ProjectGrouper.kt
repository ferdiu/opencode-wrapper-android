package it.ferdiu.opencodewrapper.auto

import it.ferdiu.opencodewrapper.api.OcSession

data class ProjectGroup(
    val directory: String?,
    val label: String,
    val sessions: List<OcSession>,
)

/**
 * Groups the (newest-first) session list by project directory for the
 * Projects tab. Group order follows first appearance, i.e. the project with
 * the most recently active session comes first. Pure JVM logic, no Android.
 */
object ProjectGrouper {

    fun group(sessions: List<OcSession>): List<ProjectGroup> =
        sessions.groupBy { it.directory }
            .map { (directory, group) ->
                ProjectGroup(
                    directory = directory,
                    // "/" is the global instance worktree: basename is empty,
                    // so it maps to "Global" like OcProject.label does.
                    label = directory?.substringAfterLast('/')?.ifEmpty { "Global" } ?: "Unknown project",
                    sessions = group,
                )
            }
}
