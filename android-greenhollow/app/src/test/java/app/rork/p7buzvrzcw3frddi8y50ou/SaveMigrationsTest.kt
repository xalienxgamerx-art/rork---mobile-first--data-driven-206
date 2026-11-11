package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.sim.NewerSaveException
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Progression
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveMigrations
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveMigrationsTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The shape of a save from before attributes, slots and discoveries existed. */
    private fun v1Save() = """
        {
          "version": 1,
          "minutes": 5000.0,
          "nextUid": 7,
          "player": {
            "name": "Wren",
            "race": "human",
            "gold": 42,
            "equipped": {"weapon": "u3", "shield": "u4"},
            "skills": {"blade": 7.5, "block": 2.0},
            "inventory": []
          }
        }
    """.trimIndent()

    @Test
    fun `a v1 save upgrades with slots, skills, attributes and a seed in place`() {
        val migrated = SaveMigrations.migrate(json.parseToJsonElement(v1Save()).jsonObject)

        assertEquals(4, SaveMigrations.versionOf(migrated))
        val player = migrated["player"]!!.jsonObject
        val equipped = player["equipped"]!!.jsonObject
        assertEquals("u3", equipped["main_hand"]!!.jsonPrimitive.content)
        assertEquals("u4", equipped["off_hand"]!!.jsonPrimitive.content)
        assertNull("the old slot names must be gone", equipped["weapon"])
        val skills = player["skills"]!!.jsonObject
        assertEquals("7.5", skills["blades"]!!.jsonPrimitive.content)
        assertEquals("2.0", skills["blocking"]!!.jsonPrimitive.content)
        assertNull(skills["blade"])
        val attributes = player["attributes"]!!.jsonObject
        for (a in Progression.ATTRIBUTES) {
            assertEquals(Progression.BASE_ATTRIBUTE, attributes[a]!!.jsonPrimitive.int)
        }
        assertEquals(Progression.STARTING_POINTS, player["attributePoints"]!!.jsonPrimitive.content.toInt())
        assertTrue("a seed must be derived so random events continue", (migrated["seed"]!!.jsonPrimitive.longOrNull ?: 0L) != 0L)
    }

    @Test
    fun `migrating twice changes nothing`() {
        val once = SaveMigrations.migrate(json.parseToJsonElement(v1Save()).jsonObject)
        val twice = SaveMigrations.migrate(once)
        assertEquals(once, twice)
    }

    @Test
    fun `a save from a newer version of the game is refused with its version`() {
        val root = json.parseToJsonElement("""{"version": 5}""").jsonObject
        val e = assertThrows(NewerSaveException::class.java) { SaveMigrations.migrate(root) }
        assertEquals(5, e.version)
    }

    @Test
    fun `a v2 save's townsfolk are stamped as simulated up to now`() {
        val v2 = """
            {
              "version": 2,
              "minutes": 394560.0,
              "npcs": [
                {"id": "bram", "x": 1.0, "z": 2.0},
                {"id": "elsa", "x": 3.0, "z": 4.0}
              ]
            }
        """.trimIndent()
        val migrated = SaveMigrations.migrate(json.parseToJsonElement(v2).jsonObject)

        assertEquals(4, SaveMigrations.versionOf(migrated))
        val list = migrated["npcs"] as kotlinx.serialization.json.JsonArray
        for (npc in list) {
            val lastSim = npc.jsonObject["lastSim"]!!.jsonPrimitive.double
            assertEquals(394560.0, lastSim, 0.001)
        }
    }

    @Test
    fun `a v4 save decodes and re-encodes byte for byte`() {
        val text = SaveStore.encode(app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame())
        val again = SaveStore.encode(SaveStore.decode(text))
        assertEquals(text, again)
    }

    @Test
    fun `a v3 save's bonds and affinities become directional opinions, nothing lost`() {
        val v3 = """
            {
              "version": 3,
              "minutes": 5000.0,
              "npcs": [
                {"id": "bram", "x": 1.0, "z": 2.0, "bonds": {"elsa": 6, "oswin": -4}, "affinity": 60}
              ]
            }
        """.trimIndent()
        val migrated = SaveMigrations.migrate(json.parseToJsonElement(v3).jsonObject)

        assertEquals(4, SaveMigrations.versionOf(migrated))
        val social = migrated["social"]!!.jsonObject
        val rels = social["relationships"]!!.jsonObject
        // Hawk's warmth for Elsa becomes an opinion keyed holder>other, directional by convention.
        val bramToElsa = rels["bram>elsa"]!!.jsonObject
        assertEquals("bram", bramToElsa["holder"]!!.jsonPrimitive.content)
        assertEquals("elsa", bramToElsa["other"]!!.jsonPrimitive.content)
        val friendship = bramToElsa["friendship"]!!.jsonPrimitive.content.toFloat()
        assertEquals("an old bond of +6 grows into real friendship", 80f, friendship, 0.5f)
        val trust = bramToElsa["trust"]!!.jsonPrimitive.content.toFloat()
        assertEquals("63", 63f, trust, 0.5f)
        // A negative bond reads as distrust, not friendship.
        val bramToOswin = rels["bram>oswin"]!!.jsonObject
        assertEquals(30f, bramToOswin["friendship"]!!.jsonPrimitive.content.toFloat(), 0.5f)
        // Affinity seeds the opinion of the player.
        val bramToPlayer = rels["bram>player"]!!.jsonObject
        assertEquals(54f, bramToPlayer["friendship"]!!.jsonPrimitive.content.toFloat(), 0.5f)
        assertEquals(48f, bramToPlayer["familiarity"]!!.jsonPrimitive.content.toFloat(), 0.5f)
        // Everyone gains an empty structured-memory store.
        val npcs = migrated["npcs"] as kotlinx.serialization.json.JsonArray
        val bram = npcs[0].jsonObject
        assertEquals(0, (bram["experiences"] as kotlinx.serialization.json.JsonArray).size)
        // And the gossip ledger starts empty.
        assertEquals(0, (social["gossip"] as kotlinx.serialization.json.JsonArray).size)
    }
}
