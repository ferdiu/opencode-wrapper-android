package it.ferdiu.opencodewrapper.auto

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectIconFactoryTest {

    private fun colors(colorKey: String?) = ProjectIconFactory.avatarColors(colorKey)

    @Test
    fun `pink maps to pink avatar colors`() {
        assertEquals(0xFF501B3F.toInt() to 0xFFE34BA9.toInt(), colors("pink"))
    }

    @Test
    fun `mint maps to mint avatar colors`() {
        assertEquals(0xFF033A34.toInt() to 0xFF95F3D9.toInt(), colors("mint"))
    }

    @Test
    fun `orange maps to orange avatar colors`() {
        assertEquals(0xFF5F2A06.toInt() to 0xFFFF802B.toInt(), colors("orange"))
    }

    @Test
    fun `purple maps to purple avatar colors`() {
        assertEquals(0xFF432155.toInt() to 0xFF9D5BD2.toInt(), colors("purple"))
    }

    @Test
    fun `cyan maps to cyan avatar colors`() {
        assertEquals(0xFF0F3058.toInt() to 0xFF369EFF.toInt(), colors("cyan"))
    }

    @Test
    fun `lime maps to lime avatar colors`() {
        assertEquals(0xFF2B3711.toInt() to 0xFFC4F042.toInt(), colors("lime"))
    }

    @Test
    fun `null maps to neutral gray fallback`() {
        assertEquals(0xFF2A2A2E.toInt() to 0xFFD4D4D8.toInt(), colors(null))
    }

    @Test
    fun `unknown color key maps to neutral gray fallback`() {
        assertEquals(0xFF2A2A2E.toInt() to 0xFFD4D4D8.toInt(), colors("blue"))
    }
}
