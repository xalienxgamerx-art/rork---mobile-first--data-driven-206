package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingRoomDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingTaxonomy
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDirectory
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentBuilder
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.LogicalBuilding
import app.rork.p7buzvrzcw3frddi8y50ou.data.ModManifest
import app.rork.p7buzvrzcw3frddi8y50ou.data.PackSource
import app.rork.p7buzvrzcw3frddi8y50ou.render.BuildingMesh
import app.rork.p7buzvrzcw3frddi8y50ou.render.MeshBuilder
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building roadmap Phase 1A: classifications, functional zones, rooms and the logical /
 * instance architecture. Everything here goes through the real content-build pathway —
 * packs in, validated registry out — or through [BuildingDirectory] over such a registry.
 */
class BuildingClassTest {

    private fun pack(vararg buildings: BuildingDef) = ContentPack(
        manifest = ModManifest(id = "town", name = "Town", version = "1.0.0", loadOrder = 10),
        buildings = buildings.toList(),
    )

    private fun build(vararg packs: ContentPack) =
        ContentBuilder.build(packs.map { PackSource(it, false) }, emptySet())

    // ---- vocabularies -----------------------------------------------------------

    @Test
    fun `taxonomy declares the full closed vocabularies`() {
        assertEquals(
            setOf(
                "residential", "commercial", "industrial", "hospitality",
                "civic", "religious", "agricultural", "storage", "mixed_use",
            ),
            BuildingTaxonomy.CLASSIFICATIONS,
        )
        assertEquals(
            setOf("residential", "commercial", "workshop", "storage", "public", "private", "staff_only"),
            BuildingTaxonomy.ZONES,
        )
        assertEquals(setOf("public", "private", "staff_only"), BuildingTaxonomy.ACCESS_LEVELS)
    }

    @Test
    fun `legacy building types map to their classification`() {
        assertEquals("residential", BuildingTaxonomy.classifyType("house"))
        assertEquals("residential", BuildingTaxonomy.classifyType("Cottage"))
        assertEquals("agricultural", BuildingTaxonomy.classifyType("farmhouse"))
        assertEquals("commercial", BuildingTaxonomy.classifyType("shop"))
        assertEquals("industrial", BuildingTaxonomy.classifyType("smithy"))
        assertEquals("hospitality", BuildingTaxonomy.classifyType("inn"))
        assertEquals("civic", BuildingTaxonomy.classifyType("well"))
        assertEquals("religious", BuildingTaxonomy.classifyType("chapel"))
        assertEquals("storage", BuildingTaxonomy.classifyType("warehouse"))
        assertNull(BuildingTaxonomy.classifyType("lighthouse"))
    }

    @Test
    fun `generated room purposes map to zones and unknown purposes map to nothing`() {
        assertEquals("residential", BuildingTaxonomy.zoneForPurpose("bedchamber"))
        assertEquals("private", BuildingTaxonomy.zoneForPurpose("Solar"))
        assertEquals("storage", BuildingTaxonomy.zoneForPurpose("cold cellar"))
        assertEquals("workshop", BuildingTaxonomy.zoneForPurpose("kitchen"))
        assertEquals("public", BuildingTaxonomy.zoneForPurpose("great hall"))
        assertEquals("staff_only", BuildingTaxonomy.zoneForPurpose("guardroom"))
        assertEquals("", BuildingTaxonomy.zoneForPurpose("observatory"))
        assertEquals("", BuildingTaxonomy.zoneForPurpose(""))
    }

    // ---- classification resolution at build time --------------------------------

    @Test
    fun `a legacy house resolves to residential with no issues`() {
        val registry = build(TestPacks.base())
        assertTrue("no cottage issues: ${registry.issues}", registry.issues.none { it.entry.endsWith("cottage") })
        assertEquals("residential", registry.buildings["cottage"]!!.classification)
    }

    @Test
    fun `an explicit classification wins over the legacy type map`() {
        // A manor typed "hall" would default to civic; the pack says residential and wins.
        val registry = build(pack(BuildingDef(id = "manor", name = "Greywarden Hall", type = "hall", x = 0f, z = 0f, classification = "residential")))
        assertEquals("residential", registry.buildings["manor"]!!.classification)
        assertTrue("no warning expected", registry.issues.none { it.entry.endsWith("manor") })
    }

    @Test
    fun `an unknown legacy type is reported and left unclassified, not guessed`() {
        val registry = build(pack(BuildingDef(id = "tower", name = "Lighthouse", type = "lighthouse", x = 0f, z = 0f)))
        assertEquals("", registry.buildings["tower"]!!.classification)
        val warning = registry.issues.single { it.entry.endsWith(":tower") && it.severity == "warning" }
        assertTrue(warning.message.contains("lighthouse"))
        assertTrue("lists the valid classifications", warning.message.contains("residential"))
        assertTrue("lists the valid classifications", warning.message.contains("mixed_use"))
    }

    @Test
    fun `an invalid explicit classification is reported and left blank`() {
        val registry = build(pack(BuildingDef(id = "inn", name = "Inn", type = "inn", x = 0f, z = 0f, classification = "Fortress")))
        assertEquals("", registry.buildings["inn"]!!.classification)
        val warning = registry.issues.single { it.entry.endsWith(":inn") && it.severity == "warning" }
        assertTrue(warning.message.contains("Fortress"))
    }

    // ---- zones and authored rooms -----------------------------------------------

    @Test
    fun `zones and rooms survive the build with purposes distinct from zones`() {
        val registry = build(
            pack(
                BuildingDef(
                    id = "inn", name = "The Gilded Stag", type = "inn", x = 0f, z = 0f,
                    classification = "hospitality",
                    zones = listOf("public", "commercial"),
                    rooms = listOf(
                        BuildingRoomDef(id = "common_room", name = "Common Room", purpose = "common room", zone = "public", floor = 0),
                        BuildingRoomDef(id = "cellar", name = "Cellar", purpose = "keeps the casks", zone = "storage", access = "staff_only", floor = 0),
                        BuildingRoomDef(id = "bedchamber", name = "West Bedchamber", purpose = "lodging", zone = "residential", access = "private", floor = 1),
                    ),
                ),
            ),
        )
        assertTrue("no issues expected: ${registry.issues}", registry.issues.isEmpty())
        val d = registry.buildings["inn"]!!
        assertEquals("hospitality", d.classification)
        assertEquals(listOf("public", "commercial"), d.zones)
        assertEquals(3, d.rooms.size)
        assertEquals("keeps the casks", d.rooms[1].purpose)
    }

    @Test
    fun `invalid room words are reported and normalised, never dropped`() {
        val registry = build(
            pack(
                BuildingDef(
                    id = "hall", name = "Hall", type = "hall", x = 0f, z = 0f,
                    rooms = listOf(
                        BuildingRoomDef(id = "a", zone = "balcony", access = "secret", floor = -2),
                        BuildingRoomDef(id = "b", zone = "public"),
                        BuildingRoomDef(id = "a", zone = "storage"),
                    ),
                ),
            ),
        )
        val d = registry.buildings["hall"]!!
        assertEquals(2, d.rooms.size)
        assertEquals("", d.rooms[0].zone)
        assertEquals("", d.rooms[0].access)
        assertEquals(0, d.rooms[0].floor)
        assertEquals("public", d.rooms[1].zone)
        val messages = registry.issues.filter { it.entry.endsWith(":hall") && it.severity == "warning" }.map { it.message }
        assertTrue(messages.any { it.contains("balcony") })
        assertTrue(messages.any { it.contains("secret") })
        assertTrue(messages.any { it.contains("twice") })
    }

    @Test
    fun `declared rooms on a procedural building are reported and left to the generator`() {
        val registry = build(
            pack(
                BuildingDef(
                    id = "manor", name = "Manor", type = "hall", x = 0f, z = 0f, procedural = true,
                    rooms = listOf(BuildingRoomDef(id = "forge", purpose = "workshop")),
                ),
            ),
        )
        assertEquals(0, registry.buildings["manor"]!!.rooms.size)
        assertTrue(registry.issues.any { it.entry.endsWith(":manor") && it.severity == "warning" && it.message.contains("grows its rooms itself") })
    }

    // ---- the common representation through BuildingDirectory --------------------

    @Test
    fun `authored rooms resolve with stable refs, floors, zones and unioned building zones`() {
        val registry = build(
            pack(
                BuildingDef(
                    id = "shop", name = "Oswin's Sundries", type = "shop", x = 0f, z = 0f,
                    classification = "mixed_use",
                    zones = listOf("commercial"),
                    rooms = listOf(
                        BuildingRoomDef(id = "shop_floor", purpose = "trade", zone = "public", floor = 0),
                        BuildingRoomDef(id = "stockroom", purpose = "stock", zone = "storage", access = "private", floor = 0),
                        BuildingRoomDef(id = "bedchamber", purpose = "lodging", zone = "residential", access = "private", floor = 1),
                    ),
                ),
            ),
        )
        val logical = BuildingDirectory(registry).logical("shop")!!
        assertEquals("mixed_use", logical.classification)
        assertEquals(setOf("commercial", "public", "storage", "residential"), logical.zones)
        val bid = logical.id
        assertEquals(
            listOf("${bid}#room:shop_floor", "${bid}#room:stockroom", "${bid}#room:bedchamber"),
            logical.rooms.map { it.ref },
        )
        assertEquals(listOf(0, 0, 1), logical.rooms.map { it.floor })
        assertEquals(listOf("public", "storage", "residential"), logical.rooms.map { it.zone })
        assertEquals(listOf("", "private", "private"), logical.rooms.map { it.access })
        assertTrue(logical.rooms.all { it.bounds == null })
    }

    @Test
    fun `procedural rooms derive from the generated plan with floor-qualified refs`() {
        val def = BuildingDef(id = "manor", name = "Greywarden Hall", type = "hall", x = 0f, z = 0f, procedural = true)
        val registry = build(pack(def))
        val logical = BuildingDirectory(registry).logical("manor")!!
        assertTrue(logical.procedural)
        assertTrue("the plan has rooms", logical.rooms.size >= 6)
        assertTrue("refs are all distinct", logical.rooms.map { it.ref }.size == logical.rooms.map { it.ref }.toSet().size)
        assertTrue("refs are floor-qualified", logical.rooms.all { it.ref.startsWith("${logical.id}#f") && "#r" in it.ref })
        // The same local room number on different floors cannot collide.
        val byFloor = logical.rooms.groupBy { it.floor }
        assertTrue(byFloor.size >= 2)
        val locals0 = byFloor.getValue(0).map { it.ref.substringAfterLast("#") }.toSet()
        val locals1 = byFloor.getValue(1).map { it.ref.substringAfterLast("#") }.toSet()
        val shared = locals0.intersect(locals1)
        assertTrue("local room numbers repeat across floors", shared.isNotEmpty())
        assertTrue(shared.all { local -> logical.rooms.count { it.ref.endsWith("#$local") } >= 2 })
        // Bounds are world-space tiles.
        val ground = byFloor.getValue(0).first()
        assertTrue(ground.bounds != null && ground.bounds!!.w > 0f && ground.bounds!!.d > 0f)
        // Generated purposes feed the zone union without guessing: zones are exactly the
        // mapped purposes' zones, and unmapped kinds (like "great chamber") map to nothing.
        assertTrue("zones are taxonomy words", logical.zones.all { it in BuildingTaxonomy.ZONES })
        assertTrue(
            "zones come only from purposes",
            logical.zones.all { z -> logical.rooms.any { BuildingTaxonomy.zoneForPurpose(it.purpose) == z } },
        )
        assertTrue(logical.rooms.all { it.zone == BuildingTaxonomy.zoneForPurpose(it.purpose) })
    }

    @Test
    fun `procedural room data is deterministic across directories and registries`() {
        val def = BuildingDef(id = "manor", name = "Greywarden Hall", type = "hall", x = 0f, z = 0f, procedural = true)
        val a = BuildingDirectory(build(pack(def))).logical("manor")!!
        val b = BuildingDirectory(build(pack(def))).logical("manor")!!
        assertEquals(a.rooms.map { it.ref }, b.rooms.map { it.ref })
        assertEquals(a.rooms.map { it.bounds }, b.rooms.map { it.bounds })
        assertEquals(a.zones, b.zones)
    }

    @Test
    fun `legacy buildings keep no fabricated rooms and procedural floors match the plan`() {
        val registry = build(TestPacks.base())
        val cottage = BuildingDirectory(registry).logical("cottage")!!
        assertTrue(cottage.rooms.isEmpty())
        assertTrue("no zones are invented for a plain legacy house", cottage.zones.isEmpty())
        assertEquals("residential", cottage.classification)
        assertTrue(!cottage.procedural)
    }

    @Test
    fun `unknown ids and missing buildings resolve to null`() {
        val registry = build(TestPacks.base())
        assertNull(BuildingDirectory(registry).logical("no_such_building"))
    }

    // ---- logical / instance architecture ----------------------------------------

    @Test
    fun `exterior and interior instance ids are distinct and share the logical id`() {
        val registry = build(TestPacks.base())
        val logical = BuildingDirectory(registry).logical("cottage")!!
        assertEquals("ext:${logical.id}", logical.exteriorInstanceId)
        assertEquals("int:${logical.id}", logical.interiorInstanceId)
        assertNotEquals(logical.exteriorInstanceId, logical.interiorInstanceId)
        assertTrue(logical.exteriorInstanceId.endsWith(logical.id))
        assertEquals("ext:", LogicalBuilding.EXTERIOR_PREFIX)
        assertEquals("int:", LogicalBuilding.INTERIOR_PREFIX)
    }

    // ---- backward compatibility --------------------------------------------------

    @Test
    fun `workplace and home resolution by id and type still work unchanged`() {
        val registry = build(TestPacks.base())
        assertEquals("base:cottage", registry.buildings["cottage"]!!.id)
        assertEquals("type lookup still finds the house", "base:cottage", registry.buildingFor("house")!!.id)
        val fisher = registry.professions["base:fisher"]!!
        assertEquals("base:harbour", fisher.workplace)
        assertTrue(registry.issues.none { it.severity == "error" })
    }

    @Test
    fun `zone and room metadata do not change rendered geometry`() {
        val plain = BuildingDef(id = "manor", name = "Manor", type = "hall", x = 0f, z = 0f, procedural = true)
        val annotated = plain.copy(
            classification = "residential",
            zones = listOf("residential", "public"),
            rooms = listOf(BuildingRoomDef(id = "ghost", purpose = "never rendered")),
        )
        val a = MeshBuilder()
        val b = MeshBuilder()
        assertTrue(BuildingMesh.emit(a, plain))
        assertTrue(BuildingMesh.emit(b, annotated))
        assertEquals("the same building produces the same geometry", a.size, b.size)
    }

    // ---- the real core pack -------------------------------------------------------

    @Test
    fun `the core pack's buildings all carry classifications and the inn and shop declare rooms`() {
        val registry = TestPacks.build(*coreAndEmberfall().toTypedArray())
        assertTrue("no error issues: ${registry.issues.filter { it.severity == "error" }}", registry.issues.none { it.severity == "error" })
        assertEquals("hospitality", registry.buildings["inn"]!!.classification)
        assertEquals("mixed_use", registry.buildings["shop"]!!.classification)
        assertEquals("industrial", registry.buildings["smithy"]!!.classification)
        assertEquals("civic", registry.buildings["well"]!!.classification)
        assertEquals("residential", registry.buildings["manor"]!!.classification)
        assertTrue(registry.buildings["inn"]!!.rooms.size >= 7)
        assertTrue(registry.buildings["shop"]!!.rooms.map { it.id }.contains("bedchamber"))
        val dir = BuildingDirectory(registry)
        val inn = dir.logical("inn")!!
        assertEquals(setOf("public", "commercial", "workshop", "storage", "staff_only", "residential"), inn.zones)
        assertEquals("staff_only", inn.rooms.first { it.ref.endsWith("cellar") }.access)
        assertEquals(1, inn.rooms.first { it.ref.endsWith("staff_quarters") }.floor)
        val manor = dir.logical("manor")!!
        assertTrue(manor.procedural && manor.rooms.isNotEmpty())
    }

    private fun coreAndEmberfall(): List<ContentPack> {
        val dir = TestPacks.moduleDir()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        return listOf("core.json", "emberfall.json").map { name ->
            json.decodeFromString<ContentPack>(File(dir, "src/main/assets/content/$name").readText())
        }
    }
}
