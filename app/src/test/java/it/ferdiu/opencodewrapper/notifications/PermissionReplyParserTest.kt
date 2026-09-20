package it.ferdiu.opencodewrapper.notifications

import it.ferdiu.opencodewrapper.api.PermissionDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionReplyParserTest {

    @Test
    fun `plain allow maps to ONCE`() {
        assertEquals(PermissionDecision.ONCE, PermissionReplyParser.parse("allow"))
        assertEquals(PermissionDecision.ONCE, PermissionReplyParser.parse("Allow it"))
        assertEquals(PermissionDecision.ONCE, PermissionReplyParser.parse("yes"))
        assertEquals(PermissionDecision.ONCE, PermissionReplyParser.parse("approve"))
    }

    @Test
    fun `allow always maps to ALWAYS`() {
        assertEquals(PermissionDecision.ALWAYS, PermissionReplyParser.parse("allow always"))
        assertEquals(PermissionDecision.ALWAYS, PermissionReplyParser.parse("always allow"))
        assertEquals(PermissionDecision.ALWAYS, PermissionReplyParser.parse("Always"))
    }

    @Test
    fun `deny words map to REJECT`() {
        assertEquals(PermissionDecision.REJECT, PermissionReplyParser.parse("deny"))
        assertEquals(PermissionDecision.REJECT, PermissionReplyParser.parse("no"))
        assertEquals(PermissionDecision.REJECT, PermissionReplyParser.parse("reject it"))
        assertEquals(PermissionDecision.REJECT, PermissionReplyParser.parse("refuse"))
    }

    @Test
    fun `negated allow maps to REJECT not ONCE`() {
        assertEquals(PermissionDecision.REJECT, PermissionReplyParser.parse("don't allow"))
        assertEquals(PermissionDecision.REJECT, PermissionReplyParser.parse("do not allow"))
    }

    @Test
    fun `deny wins over allow when both present`() {
        assertEquals(PermissionDecision.REJECT, PermissionReplyParser.parse("no, don't allow that"))
    }

    @Test
    fun `unrelated speech returns null`() {
        assertNull(PermissionReplyParser.parse("what time is it"))
        assertNull(PermissionReplyParser.parse(""))
        assertNull(PermissionReplyParser.parse("   "))
    }

    @Test
    fun `word boundaries prevent substring false positives`() {
        assertNull(PermissionReplyParser.parse("I know")) // "know" contains "no"
    }
}
