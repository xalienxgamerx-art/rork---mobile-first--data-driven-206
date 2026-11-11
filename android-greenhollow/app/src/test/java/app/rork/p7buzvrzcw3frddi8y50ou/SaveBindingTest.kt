package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.sim.BestiaryEntry
import app.rork.p7buzvrzcw3frddi8y50ou.sim.RumourEntry
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveBinding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveBindingTest {

    @Test
    fun `legacy discovered, opened, bestiary and rumour ids are rewritten to their full form`() {
        val content = TestPacks.build(TestPacks.base())
        val save = SaveGame(
            opened = mutableMapOf("chest" to 3.0, "cd:chest" to 4.0),
            discovered = mutableMapOf("vale" to 5.0),
            bestiary = mutableMapOf("wisp" to BestiaryEntry(firstSeen = 2.0, kills = 3)),
            rumours = mutableListOf(RumourEntry("hidden", 1.0, "book")),
        )

        SaveBinding.bind(save, content)

        assertTrue(save.discovered.containsKey("base:vale"))
        assertTrue(save.opened.containsKey("base:chest"))
        assertTrue("cooldown entries move with their object", save.opened.containsKey("cd:base:chest"))
        assertTrue(save.bestiary.containsKey("base:wisp"))
        assertEquals("base:hidden", save.rumours.single().id)
        assertFalse("the old keys are gone", save.discovered.containsKey("vale"))
    }

    @Test
    fun `a place discovered twice under two names counts once`() {
        val content = TestPacks.build(TestPacks.base())
        val save = SaveGame(discovered = mutableMapOf("vale" to 5.0, "base:vale" to 9.0))

        SaveBinding.bind(save, content)

        assertEquals(1, save.discovered.size)
        assertEquals(9.0, save.discovered["base:vale"]!!, 0.0)
    }

    @Test
    fun `a rumour heard twice under two names counts once`() {
        val content = TestPacks.build(TestPacks.base())
        val save = SaveGame(
            rumours = mutableListOf(RumourEntry("hidden", 1.0, "book"), RumourEntry("base:hidden", 7.0, "tavern")),
        )

        SaveBinding.bind(save, content)

        assertEquals(1, save.rumours.size)
        assertEquals("base:hidden", save.rumours.single().id)
    }

    @Test
    fun `ids no pack defines are kept, not deleted, and canonicalised where possible`() {
        val content = TestPacks.build(TestPacks.base())
        val save = SaveGame(
            discovered = mutableMapOf("nowhere_place" to 1.0),
            bestiary = mutableMapOf("mystery_beast" to BestiaryEntry(firstSeen = 4.0)),
            rumours = mutableListOf(RumourEntry("unheard_rumour", 6.0, "wall")),
        )

        SaveBinding.bind(save, content)

        assertTrue(save.discovered.containsKey("nowhere_place"))
        assertTrue(save.bestiary.containsKey("mystery_beast"))
        assertEquals("unheard_rumour", save.rumours.single().id)
    }

    @Test
    fun `binding is idempotent`() {
        val content = TestPacks.build(TestPacks.base())
        val save = SaveGame(
            opened = mutableMapOf("chest" to 3.0),
            discovered = mutableMapOf("vale" to 5.0),
            bestiary = mutableMapOf("wisp" to BestiaryEntry(firstSeen = 2.0)),
            rumours = mutableListOf(RumourEntry("hidden", 1.0, "book")),
        )

        SaveBinding.bind(save, content)
        val first = save.discovered.toMap() to save.opened.toMap() to save.bestiary.toMap()
        SaveBinding.bind(save, content)
        val second = save.discovered.toMap() to save.opened.toMap() to save.bestiary.toMap()

        assertEquals(first, second)
        assertEquals(listOf("base:hidden"), save.rumours.map { it.id })
    }
}
