package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveStore
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PlayerState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveMigrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class SaveStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun save(name: String) = SaveGame(player = PlayerState(name = name))

    private fun writeSave(dir: File, name: String) {
        SaveStore.write(dir, SaveStore.encode(save(name)))
    }

    private fun liveVersion(dir: File): Int =
        SaveMigrations.versionOf(Json.parseToJsonElement(File(dir, "world.json").readText()).jsonObject)

    @Test
    fun `a written save loads back with everything in it`() {
        val dir = tmp.newFolder()
        writeSave(dir, "Wren")

        val result = SaveStore.load(dir)

        assertNotNull(result.save)
        assertEquals("Wren", result.save!!.player.name)
        assertEquals("", result.notice)
        assertEquals(0, result.newerVersion)
    }

    @Test
    fun `backups rotate with the newest first`() {
        val dir = tmp.newFolder()
        writeSave(dir, "first")
        writeSave(dir, "second")
        writeSave(dir, "third")

        assertEquals("third", SaveStore.decode(File(dir, "world.json").readText()).player.name)
        assertEquals("second", SaveStore.decode(File(dir, "world.bak1.json").readText()).player.name)
        assertEquals("first", SaveStore.decode(File(dir, "world.bak2.json").readText()).player.name)
    }

    @Test
    fun `a corrupt live save falls back to the previous backup and is kept aside`() {
        val dir = tmp.newFolder()
        writeSave(dir, "good")
        writeSave(dir, "good2")
        File(dir, "world.json").writeText("{ this is not json")

        val result = SaveStore.load(dir)

        assertEquals("good", result.save!!.player.name)
        assertTrue(result.notice.contains("earlier copy"))
        assertTrue(File(dir, "world.corrupt.json").exists())
    }

    @Test
    fun `a newer save stops loading and older backups are not used behind the player's back`() {
        val dir = tmp.newFolder()
        writeSave(dir, "past")
        writeSave(dir, "past2")
        File(dir, "world.json").writeText("""{"version":9,"player":{"name":"Future"}}""")

        val result = SaveStore.load(dir)

        assertNull("no world may load while a newer one is on disk", result.save)
        assertEquals(9, result.newerVersion)
        assertTrue(result.notice.contains("newer"))
        // The newer file is untouched, and the older backup was not promoted.
        assertEquals(9, liveVersion(dir))
        assertEquals("past", SaveStore.decode(File(dir, "world.bak1.json").readText()).player.name)
    }

    @Test
    fun `writing refuses to overwrite a newer world`() {
        val dir = tmp.newFolder()
        File(dir, "world.json").writeText("""{"version":9,"player":{"name":"Future"}}""")

        assertThrows(IOException::class.java) { writeSave(dir, "usurper") }

        assertEquals(9, liveVersion(dir))
        assertFalse(File(dir, "world.bak1.json").exists())
    }

    @Test
    fun `restoring an older backup archives the newer world and loads the newest old one`() {
        val dir = tmp.newFolder()
        writeSave(dir, "past")
        writeSave(dir, "past2")
        File(dir, "world.json").writeText("""{"version":9,"player":{"name":"Future"}}""")

        val result = SaveStore.restoreOlderBackup(dir)

        assertEquals("past", result.save!!.player.name)
        assertEquals("past", SaveStore.decode(File(dir, "world.json").readText()).player.name)
        val archive = File(dir, SaveStore.NEWER_ARCHIVE)
        assertTrue(archive.exists())
        // The archive is a newer save on purpose: decoding it must refuse, the text is the proof.
        assertTrue(archive.readText().contains("Future"))
    }

    @Test
    fun `restoring again archives under a fresh name instead of overwriting`() {
        val dir = tmp.newFolder()
        writeSave(dir, "past")
        File(dir, "world.json").writeText("""{"version":9,"player":{"name":"Future"}}""")
        SaveStore.restoreOlderBackup(dir)
        writeSave(dir, "past3")
        File(dir, "world.json").writeText("""{"version":9,"player":{"name":"Futureer"}}""")

        SaveStore.restoreOlderBackup(dir)

        assertTrue(File(dir, SaveStore.NEWER_ARCHIVE).exists())
        assertTrue(File(dir, "world.newer.bak1.json").exists())
        assertTrue(File(dir, SaveStore.NEWER_ARCHIVE).readText().contains("Future"))
        assertTrue(File(dir, "world.newer.bak1.json").readText().contains("Futureer"))
    }

    @Test
    fun `deleting archives a newer live world instead of removing it`() {
        val dir = tmp.newFolder()
        File(dir, "world.json").writeText("""{"version":9,"player":{"name":"Future"}}""")

        SaveStore.delete(dir)

        assertFalse(File(dir, "world.json").exists())
        assertTrue(File(dir, SaveStore.NEWER_ARCHIVE).exists())
    }

    @Test
    fun `deleting removes an ordinary world and its backups`() {
        val dir = tmp.newFolder()
        writeSave(dir, "past")
        writeSave(dir, "past2")

        SaveStore.delete(dir)

        assertFalse(File(dir, "world.json").exists())
        assertFalse(File(dir, "world.bak1.json").exists())
        assertFalse(File(dir, SaveStore.NEWER_ARCHIVE).exists())
    }

    @Test
    fun `an empty directory loads as no world at all`() {
        val dir = tmp.newFolder()

        val result = SaveStore.load(dir)

        assertNull(result.save)
        assertEquals("", result.notice)
        assertEquals(0, result.newerVersion)
    }
}
