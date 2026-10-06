package it.ferdiu.opencodewrapper.auto

import it.ferdiu.opencodewrapper.api.OcProject
import it.ferdiu.opencodewrapper.api.OcSession
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalSessionMergeTest {

    private fun project(worktree: String) =
        OcProject(id = worktree, worktree = worktree, label = worktree.substringAfterLast('/').ifEmpty { "Global" })

    private fun session(id: String, projectLabel: String?) =
        OcSession(id = id, title = "t-$id", projectLabel = projectLabel, directory = "/")

    @Test
    fun `merges global sessions when no project claims the root worktree`() {
        val merged = GlobalSessionMerge.merge(
            projects = listOf(project("/dev/a")),
            globalSessions = listOf(session("ses_1", "")),
        )
        assertEquals(listOf("ses_1"), merged.map { it.id })
        // basename("/") is empty - the car layer relabels it like the server does.
        assertEquals("Global", merged.single().projectLabel)
    }

    @Test
    fun `keeps a non-empty session label`() {
        val merged = GlobalSessionMerge.merge(
            projects = emptyList(),
            globalSessions = listOf(session("ses_1", "Global")),
        )
        assertEquals("Global", merged.single().projectLabel)
    }

    @Test
    fun `merges nothing when the server already lists the root worktree as a project`() {
        val merged = GlobalSessionMerge.merge(
            projects = listOf(project("/"), project("/dev/a")),
            globalSessions = listOf(session("ses_1", "")),
        )
        assertEquals(emptyList<OcSession>(), merged)
    }

    @Test
    fun `merges nothing when the global instance has no sessions`() {
        val merged = GlobalSessionMerge.merge(
            projects = emptyList(),
            globalSessions = emptyList(),
        )
        assertEquals(emptyList<OcSession>(), merged)
    }
}
