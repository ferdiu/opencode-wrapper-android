package it.ferdiu.opencodewrapper.auto

import it.ferdiu.opencodewrapper.api.OcSession
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectGrouperTest {

    private fun session(id: String, directory: String?) =
        OcSession(id = id, title = "t-$id", projectLabel = directory?.substringAfterLast('/'), directory = directory)

    @Test
    fun `sessions group by directory keeping newest-first order within groups`() {
        val groups = ProjectGrouper.group(listOf(
            session("ses_1", "/dev/a"),
            session("ses_2", "/dev/b"),
            session("ses_3", "/dev/a"),
        ))
        assertEquals(2, groups.size)
        assertEquals("/dev/a", groups[0].directory)
        assertEquals(listOf("ses_1", "ses_3"), groups[0].sessions.map { it.id })
        assertEquals(listOf("ses_2"), groups[1].sessions.map { it.id })
    }

    @Test
    fun `group order follows most recent activity (first appearance)`() {
        val groups = ProjectGrouper.group(listOf(
            session("ses_1", "/dev/b"),
            session("ses_2", "/dev/a"),
        ))
        // ses_1 is the newest session overall, so /dev/b comes first.
        assertEquals(listOf("/dev/b", "/dev/a"), groups.map { it.directory })
    }

    @Test
    fun `labels are directory basenames`() {
        val groups = ProjectGrouper.group(listOf(session("ses_1", "/home/dev/shop-api")))
        assertEquals("shop-api", groups[0].label)
    }

    @Test
    fun `root worktree sessions group under Global label`() {
        val groups = ProjectGrouper.group(listOf(session("ses_1", "/")))
        assertEquals("Global", groups.single().label)
    }

    @Test
    fun `sessions without directory land in an unknown-project bucket`() {
        val groups = ProjectGrouper.group(listOf(
            session("ses_1", "/dev/a"),
            session("ses_2", null),
        ))
        val unknown = groups.single { it.directory == null }
        assertEquals("Unknown project", unknown.label)
        assertEquals(listOf("ses_2"), unknown.sessions.map { it.id })
    }

    @Test
    fun `empty input gives empty output`() {
        assertEquals(emptyList<ProjectGroup>(), ProjectGrouper.group(emptyList()))
    }
}
