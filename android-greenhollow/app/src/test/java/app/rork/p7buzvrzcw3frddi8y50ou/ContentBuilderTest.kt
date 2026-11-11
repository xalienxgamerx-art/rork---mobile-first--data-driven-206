package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentBuilder
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.ItemDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.LocationDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ModManifest
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.PackSource
import app.rork.p7buzvrzcw3frddi8y50ou.data.PopulationDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ResourceDef
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContentBuilderTest {

    @Test
    fun `a pack loads after the pack it depends on, however their load orders compare`() {
        val apples = ContentPack(
            ModManifest(id = "apples", name = "Apples", version = "1.2.0", loadOrder = 5),
            items = listOf(ItemDef(id = "apple", name = "Apple", value = 2)),
            locations = listOf(LocationDef(id = "orchard", name = "Orchard", x = 50f, z = 50f, radius = 30f)),
        )
        val cider = ContentPack(
            ModManifest(id = "cider", name = "Cider", version = "1.0.0", loadOrder = 2, dependencies = listOf("apples>=1.0.0")),
            resources = listOf(ResourceDef(id = "press", name = "Cider press", item = "apples:apple", x = 1f, z = 1f)),
        )
        val registry = TestPacks.build(apples, cider)

        val ciderInfo = registry.packs.first { it.manifest.id == "cider" }
        assertTrue(ciderInfo.loaded)
        assertNotNull("the cider press should resolve its cross-pack item", registry.resources["cider:press"])
        assertTrue(registry.issues.none { it.severity == "error" && it.packId == "cider" })
    }

    @Test
    fun `a pack whose dependency is missing fails to load with an error`() {
        val lonely = ContentPack(ModManifest(id = "lonely", name = "Lonely", dependencies = listOf("ghostpack")))
        val registry = TestPacks.build(TestPacks.base(), lonely)

        assertFalse(registry.packs.first { it.manifest.id == "lonely" }.loaded)
        assertTrue(registry.issues.any { it.severity == "error" && it.packId == "lonely" && it.message.contains("ghostpack") })
    }

    @Test
    fun `packs that depend on each other in a loop are all reported and none loads`() {
        val a = ContentPack(ModManifest(id = "loop_a", name = "A"), items = listOf(ItemDef(id = "a_thing", name = "A thing")))
        val b = ContentPack(ModManifest(id = "loop_b", name = "B"), items = listOf(ItemDef(id = "b_thing", name = "B thing")))
        // Written out with the dependency lists the data class cannot express in one constructor call.
        val loopA = a.copy(manifest = a.manifest.copy(dependencies = listOf("loop_b")))
        val loopB = b.copy(manifest = b.manifest.copy(dependencies = listOf("loop_a")))

        val registry = TestPacks.build(TestPacks.base(), loopA, loopB)

        assertFalse(registry.packs.first { it.manifest.id == "loop_a" }.loaded)
        assertFalse(registry.packs.first { it.manifest.id == "loop_b" }.loaded)
        assertTrue(registry.issues.count { it.severity == "error" && it.message.contains("loop", ignoreCase = true) } >= 2)
        // The rest of the world still builds.
        assertTrue(registry.buildings.isNotEmpty())
    }

    @Test
    fun `a person with a missing race is removed with an error, not a warning`() {
        val pack = ContentPack(
            ModManifest(id = "lonely", name = "Lonely"),
            npcs = listOf(NpcDef(id = "hermit", name = "Hermit", race = "dwarves", profession = "fisher", home = "cottage")),
        )
        val registry = TestPacks.build(TestPacks.base(), pack)

        assertNull(registry.npcs["lonely:hermit"])
        assertTrue(registry.issues.any { it.severity == "error" && it.packId == "lonely" && it.entry == "lonely:hermit" })
    }

    @Test
    fun `advisory problems stay warnings and the definition stays`() {
        val pack = ContentPack(
            ModManifest(id = "trinkets", name = "Trinkets"),
            items = listOf(ItemDef(id = "hat", name = "Fancy hat", slot = "hat")),
        )
        val registry = TestPacks.build(TestPacks.base(), pack)

        assertNotNull(registry.items["trinkets:hat"])
        assertTrue(registry.issues.any { it.severity == "warning" && it.packId == "trinkets" })
        assertFalse(registry.issues.any { it.severity == "error" && it.packId == "trinkets" })
    }

    @Test
    fun `two populations of one trade all exist, with unique ids and the old ids first`() {
        val early = ContentPack(
            ModManifest(id = "early", name = "Early", loadOrder = 10),
            population = listOf(PopulationDef(profession = "fisher", count = 3)),
        )
        val late = ContentPack(
            ModManifest(id = "late", name = "Late", loadOrder = 20, dependencies = listOf("early")),
            population = listOf(PopulationDef(profession = "fisher", count = 2)),
        )
        val registry = TestPacks.build(TestPacks.base(), early, late)

        // A profession id belongs to the pack that defines it, so every fisher here
        // resolves to the base pack's fisher.
        val fishers = registry.npcs.values.filter { it.profession == "base:fisher" }
        assertEquals(5, fishers.size)
        val ids = fishers.map { it.id }
        assertEquals("every generated townsfolk must have a unique id", 5, ids.toSet().size)
        // The first population keeps the plain ids old saves already know.
        assertTrue(ids.containsAll(listOf("pop_fisher_1", "pop_fisher_2", "pop_fisher_3")))
        // The second population exists too, under pack-qualified ids.
        assertTrue(ids.any { it.startsWith("pop_fisher_late_") })
    }

    @Test
    fun `a population keeps its ids when an unrelated population is added`() {
        val before = TestPacks.build(
            TestPacks.base(),
            ContentPack(ModManifest(id = "early", name = "Early"), population = listOf(PopulationDef(profession = "fisher", count = 3))),
        )
        val after = TestPacks.build(
            TestPacks.base(),
            ContentPack(ModManifest(id = "early", name = "Early"), population = listOf(PopulationDef(profession = "fisher", count = 3))),
            ContentPack(ModManifest(id = "later", name = "Later"), population = listOf(PopulationDef(profession = "fisher", count = 2))),
        )
        val beforeIds = before.npcs.values.filter { it.profession == "base:fisher" }.map { it.id }.sorted()
        val afterIds = after.npcs.values.filter { it.profession == "base:fisher" }.map { it.id }.sorted().take(3)

        assertEquals(beforeIds, afterIds)
    }

    @Test
    fun `a generated townsfolk never overwrites a hand-written person`() {
        // Only core definitions keep bare ids, so a core person called pop_fisher_1 is what a
        // generated townsfolk could ever collide with.
        val core = ContentPack(
            ModManifest(id = "core", name = "Core", loadOrder = 0),
            npcs = listOf(NpcDef(id = "pop_fisher_1", name = "The Captain", race = "base:human", profession = "fisher", home = "cottage")),
            population = listOf(PopulationDef(profession = "fisher", count = 2)),
        )
        val registry = TestPacks.build(core, TestPacks.base())

        assertEquals("The Captain", registry.npcs["pop_fisher_1"]?.name)
        val generated = registry.npcs.values.filter { it.profession == "base:fisher" && it.name != "The Captain" }
        assertEquals(2, generated.size)
        assertFalse(generated.any { it.id == "pop_fisher_1" })
    }

    @Test
    fun `the built-in packs build cleanly together`() {
        val dir = TestPacks.moduleDir()
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val sources = listOf("core.json", "emberfall.json").map { name ->
            val text = File(dir, "src/main/assets/content/$name").readText()
            PackSource(json.decodeFromString<ContentPack>(text), true)
        }
        val registry = ContentBuilder.build(sources, emptySet())

        assertTrue(
            "no errors expected, got: ${registry.issues.filter { it.severity == "error" }}",
            registry.issues.none { it.severity == "error" },
        )
        assertTrue(registry.npcs.isNotEmpty())
        assertTrue(registry.buildings.isNotEmpty())
        assertTrue(registry.dialogues.isNotEmpty())
        assertTrue(registry.packs.all { it.loaded })
    }

    @Test
    fun `version comparisons count missing parts as zero`() {
        assertEquals(-1, ContentBuilder.compareVersions("1.0", "1.0.1"))
        assertEquals(0, ContentBuilder.compareVersions("1.0", "1.0.0"))
        assertEquals(1, ContentBuilder.compareVersions("0.3", "0.2.9"))
    }
}
