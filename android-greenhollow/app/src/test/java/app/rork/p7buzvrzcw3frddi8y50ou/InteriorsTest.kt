package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.ModManifest
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.STORY
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.TILE_SIZE
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Tile
import app.rork.p7buzvrzcw3frddi8y50ou.render.InteriorMesh
import app.rork.p7buzvrzcw3frddi8y50ou.render.WorldMesh
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Appearance
import app.rork.p7buzvrzcw3frddi8y50ou.sim.GameEngine
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SAVE_VERSION
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveMigrations
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.InteractionSystem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Building interiors: entrance connections, the enter/exit transitions, the one-slot lifecycle,
 * and persistence. Everything runs through the production systems — the entrance registry, the
 * interior system, the interaction scan and the engine's own save binding — never around them.
 */
class InteriorsTest {

    private fun world(vararg packs: ContentPack): World {
        val content = if (packs.isEmpty()) TestPacks.build(TestPacks.base()) else TestPacks.build(*packs)
        return World(content, SaveGame(), InputState(), MutableSharedFlow())
    }

    private fun enterCottage(w: World) {
        val e = w.interiors.entrances.entrances("base:cottage").first()
        w.p.x = e.outX; w.p.z = e.outZ; w.p.y = 1f; w.p.yaw = e.outYaw
        assertTrue("the door opened", w.interiors.enterByEntranceId(e.id))
    }

    // ------------------------------------------------------------ entrance connections

    @Test
    fun `a building grows its entrances from its own generated plan`() {
        val w = world()
        val ents = w.interiors.entrances.entrances("base:cottage")
        assertTrue("the cottage is enterable", ents.isNotEmpty())
        assertEquals("entrance ids are unique", ents.size, ents.map { it.id }.distinct().size)
        assertTrue(
            "every entrance names the same interior instance",
            ents.all { it.instanceId == "int:base:cottage" && it.buildingId == "base:cottage" },
        )

        // The interior spawn stands on the doorway tile of the ground floor.
        val def = w.content.buildings["base:cottage"]!!
        val plan = BuildingCache.get(def)!!
        val cx = (plan.width - 1) / 2f
        val cz = (plan.height - 1) / 2f
        for (e in ents) {
            val tx = Math.round(e.inX / TILE_SIZE + cx)
            val ty = Math.round(cz - e.inZ / TILE_SIZE)
            assertEquals(
                "the spawn tile of ${e.id} is a doorway",
                Tile.DOOR,
                plan.floors.first().tiles[ty][tx],
            )
        }

        // The exterior point stands outside the walls, in the open air. The plan carries
        // empty margin tiles around the footprint, so measure against the real footprint.
        val yaw = kotlin.math.atan2(def.x, def.z)
        val c = cos(yaw); val s = sin(yaw)
        val ground = plan.floors.first()
        var minX = Int.MAX_VALUE; var maxX = 0; var minY = Int.MAX_VALUE; var maxY = 0
        for (ty in ground.tiles.indices) for (tx in ground.tiles[ty].indices) {
            if (ground.tiles[ty][tx] != Tile.EMPTY) {
                minX = minOf(minX, tx); maxX = maxOf(maxX, tx)
                minY = minOf(minY, ty); maxY = maxOf(maxY, ty)
            }
        }
        val hx = maxOf(maxX - cx, cx - minX)
        val hz = maxOf(cz - minY, maxY - cz)
        for (e in ents) {
            val lx = (e.outX - def.x) * c - (e.outZ - def.z) * s
            val lz = (e.outX - def.x) * s + (e.outZ - def.z) * c
            assertTrue(
                "the way in of ${e.id} is outside the walls",
                maxOf(abs(lx) / hx, abs(lz) / hz) > 1f,
            )
        }
    }

    @Test
    fun `the generated hall grows a door at the front and one at the back`() {
        val w = world()
        val sides = w.interiors.entrances.entrances("base:cottage").map { it.side }
        assertTrue("a front door exists", "front" in sides)
        assertTrue("a back door exists", "back" in sides)
    }

    @Test
    fun `a well is open air and has no door`() {
        val well = ContentPack(
            ModManifest(id = "extra", name = "Extra"),
            buildings = listOf(BuildingDef(id = "well", name = "Village Well", type = "well", x = 5f, z = 30f, interact = "wash")),
        )
        val w = world(TestPacks.base(), well)
        assertTrue("wells stay outdoors", w.interiors.entrances.entrances("extra:well").isEmpty())
    }

    // ------------------------------------------------------------ transitions

    @Test
    fun `stepping through a door moves the player inside and back out again`() {
        val w = world()
        val e = w.interiors.entrances.entrances("base:cottage").first()
        val stoodX = e.outX
        val stoodZ = e.outZ
        w.p.x = stoodX; w.p.z = stoodZ; w.p.y = 1f; w.p.yaw = e.outYaw

        w.interiors.enterByEntranceId(e.id)

        assertEquals("the player is inside", "int:base:cottage", w.p.location)
        assertTrue(
            "the player was moved to the doorway's inside",
            abs(w.p.x - stoodX) + abs(w.p.z - stoodZ) > 0.5f,
        )
        val rec = w.save.interiors["int:base:cottage"]
        assertNotNull("the visit is recorded in the save", rec)
        assertEquals(1, rec!!.visits)

        w.interiors.exit()

        assertEquals("the player is outdoors again", "", w.p.location)
        assertEquals("back where they stepped in", stoodX, w.p.x, 0.01f)
        assertEquals(stoodZ, w.p.z, 0.01f)
    }

    @Test
    fun `leaving by the far door puts the player outside that door`() {
        val w = world()
        val ents = w.interiors.entrances.entrances("base:cottage")
        val front = ents.first { it.side == "front" }
        val back = ents.first { it.side == "back" }

        w.p.x = front.outX; w.p.z = front.outZ; w.p.y = 1f
        w.interiors.enter(front)
        // Cross the hall to the other door, and lose the memory of the way came in.
        w.p.x = back.inX; w.p.z = back.inZ
        w.p.returnX = 0f; w.p.returnZ = 0f

        w.interiors.exit(w.interiors.entrances.byId(back.id))

        assertEquals("", w.p.location)
        assertEquals("out of the far door", back.outX, w.p.x, 0.01f)
        assertEquals(back.outZ, w.p.z, 0.01f)
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    fun `entering another building replaces the first without losing either`() {
        val hamlet = ContentPack(
            ModManifest(id = "hamlet", name = "Hamlet"),
            buildings = listOf(BuildingDef(id = "mill", name = "Mill House", type = "house", x = 30f, z = 60f)),
        )
        val w = world(TestPacks.base(), hamlet)
        val a = w.interiors.entrances.entrances("base:cottage").first()
        val b = w.interiors.entrances.entrances("hamlet:mill").first()

        w.p.x = a.outX; w.p.z = a.outZ; w.p.y = 1f
        w.interiors.enter(a)
        val meshA = InteriorMesh.get(w.interiors.active)
        assertEquals("int:base:cottage", w.interiors.active!!.instanceId)

        w.p.x = b.outX; w.p.z = b.outZ; w.p.y = 1f
        w.interiors.enter(b)

        assertEquals("one interior is loaded at a time", "int:hamlet:mill", w.interiors.active!!.instanceId)
        val meshB = InteriorMesh.get(w.interiors.active)
        assertTrue("the mesh slot was replaced, not accumulated", meshA !== meshB)
        assertTrue("both buildings have geometry", meshB!!.vertexCount > 0)
        assertTrue(
            "the first interior's visit survives in the save",
            w.save.interiors.containsKey("int:base:cottage"),
        )
        assertNull("outdoors, there is no interior mesh", InteriorMesh.get(null))
    }

    @Test
    fun `the interior has its own walls to bump into and floors to stand on`() {
        val w = world()
        enterCottage(w)
        val a = w.interiors.active!!
        assertTrue("collision was built", a.boxes.isNotEmpty())

        // Drop the player inside the first solid tile: the push-out frees them.
        val (level, box) = a.boxes.first()
        assertEquals("ground-floor boxes", 0, level)
        val pos = floatArrayOf(box.cx, box.cz)
        w.interiors.collide(pos, 0.45f)
        assertTrue(
            "pushed out of the wall",
            abs(pos[0] - box.cx) > box.hw || abs(pos[1] - box.cz) > box.hd,
        )

        // The hall floor is underfoot, and the stair block reaches the next floor.
        val spot = w.interiors.serviceSpot(a)
        assertEquals(0.12f, w.interiors.groundY(spot[0], spot[1], 0.12f), 0.01f)
        val plan = a.plan
        val scx = (plan.width - 1) / 2f
        val scz = (plan.height - 1) / 2f
        val stairX = (plan.stairs.x + 2 - scx) * TILE_SIZE
        val stairZ = (scz - plan.stairs.y) * TILE_SIZE
        assertEquals("the top step reaches the next floor", STORY, w.interiors.groundY(stairX, stairZ, 2.5f), 0.05f)
    }

    // ------------------------------------------------------------ interaction targets

    @Test
    fun `the Use button finds doors outside and the way out inside`() {
        val w = world()
        w.interaction = InteractionSystem(w)
        val e = w.interiors.entrances.entrances("base:cottage").first()

        w.p.x = e.outX; w.p.z = e.outZ; w.p.y = 1f
        w.interaction.findTarget()
        assertEquals("the door is offered outside", "door", w.target?.kind)
        assertEquals(e.id, w.target?.id)

        w.interiors.enterByEntranceId(e.id)
        w.p.x = e.inX; w.p.z = e.inZ
        w.interaction.findTarget()
        assertEquals("the way out is offered inside", "exit", w.target?.kind)
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun `an interior visit and an inside location survive a save round trip`() {
        val w = world()
        w.save.minutes = 500.0
        enterCottage(w)

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val encoded = json.encodeToString(SaveGame.serializer(), w.save)
        val decoded = json.decodeFromString(SaveGame.serializer(), encoded)

        assertEquals("the location came back", "int:base:cottage", decoded.player.location)
        val rec = decoded.interiors["int:base:cottage"]
        assertNotNull("the ledger entry came back", rec)
        assertEquals("base:cottage", rec!!.buildingId)
        assertEquals(1, rec.visits)
        assertEquals(500.0, rec.lastVisited, 0.0)
        assertTrue(
            "no transient geometry is saved",
            !encoded.contains("\"boxes\"") && !encoded.contains("\"mesh\""),
        )

        // Loading the save reactivates the interior the player was inside.
        val engine = GameEngine(w.content, decoded)
        assertNotNull("the interior is live again after load", engine.activeInterior())
        assertEquals("int:base:cottage", decoded.player.location)
    }

    @Test
    fun `a world saved before interiors loads and starts outdoors`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val v4 = json.encodeToString(SaveGame.serializer(), SaveGame()).let { raw ->
            val root = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
            root.remove("interiors")
            root["version"] = JsonPrimitive(4)
            val player = root["player"]!!.jsonObject.toMutableMap()
            for (field in listOf("location", "returnX", "returnY", "returnZ", "returnYaw")) player.remove(field)
            root["player"] = JsonObject(player)
            JsonObject(root)
        }

        val loaded = json.decodeFromString(
            SaveGame.serializer(),
            json.encodeToString(JsonObject.serializer(), SaveMigrations.migrate(v4)),
        )

        assertEquals("the save was upgraded", SAVE_VERSION, loaded.version)
        assertTrue("no interiors were invented", loaded.interiors.isEmpty())
        assertEquals("the player starts outdoors", "", loaded.player.location)
    }

    @Test
    fun `a save written inside a building no pack defines puts the player back outside`() {
        val content = TestPacks.build(TestPacks.base())
        val save = GameEngine.newGame(content, "Wren", "base:human", content.backgrounds.keys.first(), Appearance(), emptyMap())
        save.player.location = "int:base:nowhere"
        save.player.returnX = 3f; save.player.returnY = 5f; save.player.returnZ = 7f; save.player.returnYaw = 1f

        val engine = GameEngine(content, save)

        assertEquals("the impossible interior was let go", "", engine.withState { it.player.location })
        assertEquals("returned to the remembered spot", 3f, engine.withState { it.player.x }, 0.001f)
        assertEquals(7f, engine.withState { it.player.z }, 0.001f)
    }

    // ------------------------------------------------------------ regression

    @Test
    fun `loading an interior never touches the overworld's static batch`() {
        val w = world()
        val mesh = WorldMesh.get(w.content, 0)
        val before = mesh.vertexCount

        enterCottage(w)

        assertSame("the same overworld mesh is served", mesh, WorldMesh.get(w.content, 0))
        assertEquals("the overworld grew no interior geometry", before, WorldMesh.get(w.content, 0).vertexCount)
    }
}
