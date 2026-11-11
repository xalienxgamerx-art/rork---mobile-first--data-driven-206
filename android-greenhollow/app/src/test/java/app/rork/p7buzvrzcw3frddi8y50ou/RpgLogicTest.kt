package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.ConditionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.ModManifest
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.PopulationDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Appearance
import app.rork.p7buzvrzcw3frddi8y50ou.sim.GameEngine
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Npc
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Progression
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.DialogueSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.DiscoverySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.InventorySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.ProgressionSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.TradeSystem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RpgLogicTest {

    /** A running world built from the synthetic packs, with the systems the tests touch. */
    private fun world(): World {
        val w = World(TestPacks.build(TestPacks.base()), SaveGame(), app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState(), MutableSharedFlow())
        w.progression = ProgressionSystem(w)
        w.inventory = InventorySystem(w)
        w.discovery = DiscoverySystem(w)
        w.progression.recalc()
        return w
    }

    private fun npc(affinity: Int) = Npc(
        NpcDef(id = "trader", name = "Trader", profession = "fisher"),
        ProfessionDef(id = "fisher", name = "Fisher"),
        NpcState(id = "trader", affinity = affinity),
    )

    // ------------------------------------------------------------ progression

    @Test
    fun `the rules of growth are the documented curves`() {
        assertEquals(6f, Progression.xpForRank(0), 0.001f)
        assertEquals(4f + 3, Progression.levelNeed(3), 0.001f)
        assertEquals(0f, Progression.priceShift(Progression.BASE_ATTRIBUTE, 0f), 0.0001f)
        assertEquals(0, Progression.affinityBonus(Progression.BASE_ATTRIBUTE))
        assertEquals(100f, Progression.addXp(99f, 1000f), 0.001f)
    }

    @Test
    fun `practising a skill raises it and levels the character up`() {
        val w = world()

        w.progression.gainXp("foraging", 500f)

        assertTrue("the skill grew to a high rank", w.progression.skill("foraging") > 20f)
        assertTrue("ranks feed the level bar", w.p.level > 1)
        assertTrue("levels grant attribute points", w.p.attributePoints > 0)
    }

    @Test
    fun `spending a point raises the attribute and fills the new health`() {
        val w = world()
        val beforeHealth = w.progression.maxHealth
        w.p.attributePoints = 1
        w.p.health = w.progression.maxHealth - 10f
        val healthAtSpend = w.p.health

        w.progression.spend("endurance")

        assertEquals(Progression.BASE_ATTRIBUTE + 1, w.progression.base("endurance"))
        assertEquals("the point was spent", 0, w.p.attributePoints)
        assertTrue(w.progression.maxHealth > beforeHealth)
        // Only the newly gained health is filled in; an old gap stays.
        assertEquals(healthAtSpend + (w.progression.maxHealth - beforeHealth), w.p.health, 0.001f)
    }

    @Test
    fun `spending without points does nothing`() {
        val w = world()
        w.p.attributePoints = 0
        val base = w.progression.base("strength")

        w.progression.spend("strength")

        assertEquals(base, w.progression.base("strength"))
    }

    // ------------------------------------------------------------ prices

    @Test
    fun `a friendlier trader charges less and pays more`() {
        val w = world()
        val trade = TradeSystem(w)
        val friendly = npc(affinity = 100)
        val cold = npc(affinity = 0)

        assertTrue(trade.buyPrice(friendly, 100) < trade.buyPrice(cold, 100))
        assertTrue(trade.sellPrice(friendly, 100) > trade.sellPrice(cold, 100))
    }

    @Test
    fun `charm and trading shift prices in the player's favour`() {
        val w = world()
        val trade = TradeSystem(w)
        val plain = npc(affinity = 40)

        val plainBuy = trade.buyPrice(plain, 100)
        val plainSell = trade.sellPrice(plain, 100)
        w.p.attributes["charm"] = 16
        w.p.skills["trading"] = 50f
        w.progression.recalc()

        assertTrue(trade.buyPrice(plain, 100) < plainBuy)
        assertTrue(trade.sellPrice(plain, 100) > plainSell)
    }

    @Test
    fun `prices never fall below one gold`() {
        val w = world()
        val trade = TradeSystem(w)
        val friendly = npc(affinity = 100)
        w.p.attributes["charm"] = 20
        w.p.skills["trading"] = 100f
        w.progression.recalc()

        assertTrue(trade.buyPrice(friendly, 1) >= 1)
        assertTrue(trade.sellPrice(friendly, 1) >= 1)
    }

    // ------------------------------------------------------------ dialogue conditions

    @Test
    fun `dialogue conditions read the world through one shared check`() {
        val w = world()
        w.dialogue = DialogueSystem(w, MutableStateFlow(null))
        val speaker = npc(affinity = 60)
        w.p.gold = 20
        w.save.flags.add("met_hermit")

        assertTrue(w.dialogue.holds(ConditionDef(type = "affinity", min = 50f), speaker))
        assertFalse(w.dialogue.holds(ConditionDef(type = "affinity", min = 70f), speaker))
        assertTrue("negation inverts", w.dialogue.holds(ConditionDef(type = "affinity", min = 70f, negate = true), speaker))
        assertTrue(w.dialogue.holds(ConditionDef(type = "gold", min = 10f, max = 50f), speaker))
        assertTrue(w.dialogue.holds(ConditionDef(type = "flag", key = "met_hermit"), speaker))
        assertFalse(w.dialogue.holds(ConditionDef(type = "flag", key = "met_hermit", negate = true), speaker))
        assertTrue("an unknown condition type passes rather than silencing a line", w.dialogue.holds(ConditionDef(type = "moon_phase"), speaker))
    }

    @Test
    fun `a place condition holds once the place is discovered`() {
        val w = world()
        w.dialogue = DialogueSystem(w, MutableStateFlow(null))
        val speaker = npc(affinity = 40)

        assertFalse(w.dialogue.holds(ConditionDef(type = "place", key = "vale"), speaker))
        w.save.discovered["base:vale"] = 1.0
        assertTrue(w.dialogue.holds(ConditionDef(type = "place", key = "vale"), speaker))
    }

    // ------------------------------------------------------------ a whole world

    @Test
    fun `a fresh world starts from the built-in packs with villagers in it`() {
        val folk = ContentPack(
            ModManifest(id = "folk", name = "Folk"),
            population = listOf(PopulationDef(profession = "fisher", count = 2)),
        )
        val content = TestPacks.build(TestPacks.base(), folk)
        val save = GameEngine.newGame(content, "Wren", "base:human", content.backgrounds.keys.first(), Appearance(), emptyMap())

        val engine = GameEngine(content, save)

        assertTrue("villagers were built and recorded in the save", engine.withState { it.npcs }.isNotEmpty())
        assertEquals("Wren", engine.withState { it.player.name })
        assertNotNull(engine.withState { it.player.attributes["charm"] })
    }
}
