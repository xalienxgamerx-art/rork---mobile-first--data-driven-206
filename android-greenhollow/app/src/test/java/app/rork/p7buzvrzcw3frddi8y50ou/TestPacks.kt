package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BackgroundDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentBuilder
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentRegistry
import app.rork.p7buzvrzcw3frddi8y50ou.data.CreatureDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.InteractableDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ItemDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.LocationDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.LootDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ModManifest
import app.rork.p7buzvrzcw3frddi8y50ou.data.PackSource
import app.rork.p7buzvrzcw3frddi8y50ou.data.PopulationDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.RaceDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ResourceDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.RumourDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.SpotDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.StackDef
import java.io.File

/** Small synthetic packs shared by the content, save-binding and role-playing tests. */
object TestPacks {

    /**
     * A complete little world: one race, a fisher's trade with a work spot, a house, an item,
     * a place, a chest, a creature and a rumour. One generated townsfolk keeps the settlement
     * populated so role-playing tests have someone to talk to.
     */
    fun base(id: String = "base", version: String = "1.0.0", loadOrder: Int = 10, deps: List<String> = emptyList()) = ContentPack(
        manifest = ModManifest(id = id, name = id.replaceFirstChar { it.uppercase() }, version = version, loadOrder = loadOrder, dependencies = deps),
        races = listOf(RaceDef(id = "human", name = "Human", skinTones = listOf("#D9A77F", "#B07B4F"))),
        backgrounds = listOf(BackgroundDef(id = "urchin", name = "Urchin", gold = 15, skills = mapOf("foraging" to 2f))),
        professions = listOf(ProfessionDef(id = "fisher", name = "Fisher", workplace = "harbour", trader = true, goods = listOf(StackDef("pearl", 2)))),
        buildings = listOf(BuildingDef(id = "cottage", name = "Cottage", type = "house", x = 0f, z = 40f)),
        spots = listOf(SpotDef(id = "harbour", name = "Harbour", x = 10f, z = 20f, gate = "south")),
        items = listOf(ItemDef(id = "pearl", name = "Pearl", value = 12)),
        locations = listOf(LocationDef(id = "vale", name = "The Vale", x = 0f, z = 0f, radius = 200f)),
        resources = listOf(ResourceDef(id = "reeds", name = "Reeds", item = "pearl", x = 2f, z = 3f)),
        interactables = listOf(
            InteractableDef(id = "chest", name = "Old chest", kind = "container", x = 1f, z = 2f, loot = listOf(LootDef("pearl", 1f, 1))),
        ),
        creatures = listOf(CreatureDef(id = "wisp", name = "Wisp", territory = "vale")),
        rumours = listOf(RumourDef(id = "hidden", title = "The hidden mill")),
    )

    fun build(vararg packs: ContentPack, disabled: Set<String> = emptySet()): ContentRegistry =
        ContentBuilder.build(packs.map { PackSource(it, false) }, disabled)

    /** The app module's directory, found from whatever directory the test runner starts in. */
    fun moduleDir(): File {
        var dir = File(System.getProperty("user.dir")!!)
        while (dir != null && !File(dir, "src/main/assets/content/core.json").exists()) {
            dir = dir.parentFile
        }
        return checkNotNull(dir) { "Could not find the app module directory from ${System.getProperty("user.dir")}" }
    }
}
