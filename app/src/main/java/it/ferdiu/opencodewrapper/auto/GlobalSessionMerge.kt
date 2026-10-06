package it.ferdiu.opencodewrapper.auto

import it.ferdiu.opencodewrapper.api.OcProject
import it.ferdiu.opencodewrapper.api.OcSession

/**
 * The server's global project (worktree "/") is absent from GET /project, so
 * the car home screen fetches its sessions separately and merges them into
 * the combined session list via [merge]. Pure JVM logic, no Android.
 */
object GlobalSessionMerge {

    /** Display label of the global instance, matching what the server itself
     *  reports for the "/" worktree (see [OcProject.label]). */
    private const val GLOBAL_LABEL = "Global"

    /** The global instance's sessions, relabeled for display, or an empty
     *  list when nothing should be merged: either the server already reports
     *  a "/" project (its own per-project fetch covers those sessions) or the
     *  global instance has no sessions at all. */
    fun merge(projects: List<OcProject>, globalSessions: List<OcSession>): List<OcSession> {
        if (projects.any { it.worktree == "/" } || globalSessions.isEmpty()) return emptyList()
        // basename("/") is empty, so a session copied straight from
        // listSessions carries no usable label - apply "Global" instead.
        return globalSessions.map { it.copy(projectLabel = it.projectLabel?.ifEmpty { null } ?: GLOBAL_LABEL) }
    }
}
