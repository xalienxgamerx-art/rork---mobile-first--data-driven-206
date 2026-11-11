package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.ConditionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.CreatureDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ScheduleDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ScheduleEntryDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Calendar
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Creature
import app.rork.p7buzvrzcw3frddi8y50ou.sim.CreatureState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcActions
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveBinding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveStore
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.DialogueSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.DiscoverySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.InventorySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.NeedsSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.NpcSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.ProgressionSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.ScheduleSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.SocialSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.TimeSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.TradeSystem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The living world: needs that wear away and mend, day schedules, decisions in priority order,
 * and lives that go on far from the player. Everything here runs on the JVM against a small
 * hand-authored world.
 */
class NpcLifeTest {

    /** The base world plus a scheduled trade, townsfolk to live it, and one hostile beast. */
    private fun lifePack(): ContentPack = TestPacks.base().copy(
        professions = listOf(
            ProfessionDef(id = "fisher", name = "Fisher", workplace = "harbour", schedule = "fisher_routine"),
        ),
        schedules = listOf(
            ScheduleDef(
                id = "fisher_routine",
                name = "River days",
                entries = listOf(
                    ScheduleEntryDef(start = 21f, end = 6f, activity = "sleep", target = "home"),
                    ScheduleEntryDef(start = 6f, end = 14f, activity = "work", target = "work"),
                    // Overlapping higher-priority windows win: a midday stroll beats the nets.
                    ScheduleEntryDef(start = 12f, end = 13f, activity = "wander", priority = 2),
                    ScheduleEntryDef(start = 14f, end = 16f, activity = "wander"),
                    ScheduleEntryDef(start = 16f, end = 20f, activity = "socialize"),
                    ScheduleEntryDef(start = 20f, end = 21f, activity = "eat", target = "home"),
                    // A frightened person rests instead, whatever the hour asks of others.
                    ScheduleEntryDef(
                        start = 0f, end = 24f, activity = "rest", priority = 1,
                        conditions = listOf(ConditionDef(type = "need", key = "safety", max = 30f)),
                    ),
                ),
            ),
        ),
        npcs = listOf(
            NpcDef(id = "hawk", name = "Hawk", profession = "fisher", home = "cottage"),
            NpcDef(id = "swift", name = "Swift", profession = "fisher", home = "cottage", needRates = mapOf("hunger" to 2f)),
            NpcDef(id = "robin", name = "Robin", profession = "fisher", home = "cottage"),
        ),
        creatures = listOf(
            CreatureDef(id = "wolf", name = "Wolf", aggressive = true, territory = "vale", count = 1, damage = 5f),
        ),
    )

    /** A running world with the systems a villager's life touches. */
    private fun simWorld(save: SaveGame = SaveGame(minutes = morning()), pack: ContentPack = lifePack()): World {
        val content = TestPacks.build(pack)
        val w = World(content, save, InputState(), MutableSharedFlow())
        SaveBinding.bind(save, content)
        w.progression = ProgressionSystem(w)
        w.inventory = InventorySystem(w)
        w.dialogue = DialogueSystem(w, MutableStateFlow(null))
        w.schedules = ScheduleSystem(w)
        w.social = SocialSystem(w)
        w.npcSim = NpcSystem(w)
        w.needs = NeedsSystem(w)
        w.time = TimeSystem(w)
        w.trade = TradeSystem(w)
        w.discovery = DiscoverySystem(w)
        w.progression.recalc()
        w.npcSim.build()
        return w
    }

    /** Day 3, eight in the morning: work hours, safe daylight. */
    private fun morning() = 3 * 1440.0 + 8 * 60.0

    // ------------------------------------------------------------ needs

    @Test
    fun `needs wear away as the clock runs, faster for the hungrier constitution`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!
        val swift = w.npcById[w.content.npcs.canonical("swift")]!!
        for (n in listOf(hawk, swift)) {
            n.s.hunger = 100f
            n.s.fatigue = 100f
            n.s.social = 100f
            n.decideAt = w.save.minutes + 999.0 // no decisions, only the passing of the day
        }

        w.npcSim.step(hawk, 2f, detailed = false)
        w.npcSim.step(swift, 2f, detailed = false)

        val hawkDrop = 100f - hawk.s.hunger
        val swiftDrop = 100f - swift.s.hunger
        assertTrue("needs wear away", hawkDrop > 0f)
        assertTrue("Swift's hunger runs at twice the rate", swiftDrop > hawkDrop)
        assertEquals(hawkDrop * 2f, swiftDrop, 0.01f)
    }

    @Test
    fun `eating mends hunger and sleeping mends fatigue`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!
        hawk.s.hunger = 30f
        hawk.s.fatigue = 20f
        hawk.s.action = NpcActions.EAT
        w.npcSim.step(hawk, 2f, detailed = false)
        val fed = hawk.s.hunger

        hawk.s.action = NpcActions.SLEEP
        w.npcSim.step(hawk, 2f, detailed = false)

        assertTrue("hunger rose while eating", fed > 30f)
        assertTrue("fatigue rose while asleep", hawk.s.fatigue > 20f)
    }

    // ------------------------------------------------------------ schedules and decisions

    @Test
    fun `the schedule sends a person to work by day and to bed at night`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!

        val entryAtWork = w.schedules.entryFor(hawk, morning())
        assertEquals("work", entryAtWork!!.activity)

        val night = morning() - 8 * 60.0 + 23 * 60.0 // the same day, eleven at night
        val entryAtNight = w.schedules.entryFor(hawk, night)
        assertEquals("sleep", entryAtNight!!.activity)

        // The same minute always resolves to the same window.
        assertEquals(entryAtWork, w.schedules.entryFor(hawk, morning()))
    }

    @Test
    fun `a higher-priority window wins its overlap`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!

        val midday = morning() + 4.5 * 60.0 // half past twelve, inside both work and the stroll
        assertEquals("wander", w.schedules.entryFor(hawk, midday)!!.activity)
        assertEquals(2, w.schedules.entryFor(hawk, midday)!!.priority)
    }

    @Test
    fun `a condition on a window is checked, not assumed`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!

        hawk.s.safety = 80f
        assertNotEquals("rest", w.schedules.entryFor(hawk, morning())!!.activity)
        hawk.s.safety = 10f
        assertEquals("rest", w.schedules.entryFor(hawk, morning())!!.activity)
    }

    @Test
    fun `the day's window decides the action and its destination`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!
        hawk.decideAt = w.save.minutes - 1.0

        w.npcSim.step(hawk, 0.05f, detailed = true)

        assertEquals(NpcActions.WORK, hawk.s.action)
        assertEquals("work", hawk.s.actionTarget)
        assertEquals("Harbour", hawk.destName)
    }

    @Test
    fun `critical hunger overrides the schedule`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!
        hawk.s.hunger = 10f
        hawk.decideAt = w.save.minutes - 1.0

        w.npcSim.step(hawk, 0.05f, detailed = true)

        assertEquals(NpcActions.EAT, hawk.s.action)
        assertEquals(hawk.homeName, hawk.destName)
    }

    @Test
    fun `a person without a template keeps the old scored routine`() {
        val w = simWorld(pack = lifePack().copy(professions = listOf(ProfessionDef(id = "fisher", name = "Fisher", workplace = "harbour"))))
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!
        hawk.decideAt = w.save.minutes - 1.0

        w.npcSim.step(hawk, 0.05f, detailed = true)

        assertTrue(hawk.s.action in NpcActions.ALL)
        // Work hours with a workplace: the old routine still sends him out to earn.
        assertEquals(NpcActions.WORK, hawk.s.action)
    }

    // ------------------------------------------------------------ danger

    @Test
    fun `a beast close by drains safety and sends the person fleeing home`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!
        val wolfDef = w.content.creatures[w.content.creatures.canonical("wolf")]!!
        w.creatures += Creature(wolfDef, CreatureState("wolf#0", "wolf", x = hawk.s.x + 3f, z = hawk.s.z, health = 10f))
        val before = hawk.s.safety
        hawk.decideAt = w.save.minutes - 1.0

        w.npcSim.step(hawk, 0.05f, detailed = true)

        assertEquals(NpcActions.FLEE, hawk.s.action)
        assertTrue("safety fell while the beast was near", hawk.s.safety < before)
        assertEquals(hawk.homeName, hawk.destName)
    }

    @Test
    fun `a fallen beast is no threat at all`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!
        val wolfDef = w.content.creatures[w.content.creatures.canonical("wolf")]!!
        w.creatures += Creature(wolfDef, CreatureState("wolf#0", "wolf", x = hawk.s.x + 3f, z = hawk.s.z, health = 0f))
        hawk.decideAt = w.save.minutes - 1.0

        w.npcSim.step(hawk, 0.05f, detailed = true)

        assertEquals(NpcActions.WORK, hawk.s.action)
    }

    // ------------------------------------------------------------ tiers and far-off lives

    @Test
    fun `the tier follows the distance from the player`() {
        val w = simWorld()
        val p = w.p
        p.x = 0f
        p.z = 0f
        w.npcById[w.content.npcs.canonical("hawk")]!!.s.x = 1f
        w.npcById[w.content.npcs.canonical("hawk")]!!.s.z = 1f
        w.npcById[w.content.npcs.canonical("swift")]!!.s.x = 100f
        w.npcById[w.content.npcs.canonical("swift")]!!.s.z = 0f
        w.npcById[w.content.npcs.canonical("robin")]!!.s.x = 300f
        w.npcById[w.content.npcs.canonical("robin")]!!.s.z = 0f

        w.npcSim.update(0.016f)

        assertEquals(0, w.npcById[w.content.npcs.canonical("hawk")]!!.tier)
        assertEquals(1, w.npcById[w.content.npcs.canonical("swift")]!!.tier)
        assertEquals(2, w.npcById[w.content.npcs.canonical("robin")]!!.tier)
    }

    @Test
    fun `a far-off life catches up on needs without the frame loop`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!
        val now = morning() + 15 * 60.0 // eleven at night: the schedule says sleep
        w.save.minutes = now
        hawk.s.lastSim = now - 120.0
        hawk.s.hunger = 80f
        hawk.s.fatigue = 40f
        hawk.s.x = 400f
        hawk.s.z = 400f

        w.npcSim.catchUp(hawk, now)

        assertEquals(now, hawk.s.lastSim, 0.001)
        assertTrue("two hours of hunger passed", hawk.s.hunger < 71f)
        assertEquals(NpcActions.SLEEP, hawk.s.action)
        assertTrue("settled indoors at home", hawk.s.indoors)
        assertEquals(hawk.homeDoor[0], hawk.s.x, 0.01f)
        assertEquals(hawk.homeDoor[1], hawk.s.z, 0.01f)
    }

    @Test
    fun `a catch-up never reaches further than two hours however long the world was away`() {
        val w = simWorld()
        val hawk = w.npcById[w.content.npcs.canonical("hawk")]!!
        val now = morning()
        w.save.minutes = now
        hawk.s.lastSim = now - 100_000.0
        hawk.s.hunger = 100f

        w.npcSim.catchUp(hawk, now)

        assertTrue(hawk.s.hunger > 89f) // 100 minus at most two hours of appetite
        assertEquals(now, hawk.s.lastSim, 0.001)
    }

    @Test
    fun `fast-forward advances distant lives through the same catch-up`() {
        val w = simWorld()
        val robin = w.npcById[w.content.npcs.canonical("robin")]!!
        robin.s.x = 300f
        robin.s.z = 300f
        robin.s.lastSim = w.save.minutes
        val before = robin.s.hunger

        w.time.fastForward(60.0, sleeping = false)

        assertEquals(w.save.minutes, robin.s.lastSim, 0.001)
        assertTrue(robin.s.hunger < before)
    }

    // ------------------------------------------------------------ persistence

    @Test
    fun `a saved life reloads with its needs, action and last-simulated minute`() {
        val save = SaveGame(minutes = 1000.0)
        save.npcs += NpcState(id = "hawk", hunger = 42f, fatigue = 31f, social = 55f, safety = 12.5f, action = NpcActions.SLEEP, lastSim = 999.0)

        val loaded = SaveStore.decode(SaveStore.encode(save)).npcs.first()

        assertEquals("hawk", loaded.id)
        assertEquals(42f, loaded.hunger, 0.001f)
        assertEquals(31f, loaded.fatigue, 0.001f)
        assertEquals(55f, loaded.social, 0.001f)
        assertEquals(12.5f, loaded.safety, 0.001f)
        assertEquals(NpcActions.SLEEP, loaded.action)
        assertEquals(999.0, loaded.lastSim, 0.001)
    }

    @Test
    fun `impossible needs from a damaged save are repaired, not kept`() {
        val save = SaveGame(minutes = 1000.0)
        val n = NpcState(id = "hawk", hunger = Float.NaN, fatigue = Float.POSITIVE_INFINITY, safety = -30f, lastSim = 5000.0, action = "flying")
        save.npcs += n

        n.sanitize(save.minutes)
        val decoded = SaveStore.decode(SaveStore.encode(save)).npcs.first()
        decoded.sanitize(save.minutes)

        assertEquals(70f, n.hunger, 0.001f)
        assertEquals(70f, n.fatigue, 0.001f)
        assertEquals(0f, n.safety, 0.001f)
        assertEquals(1000.0, n.lastSim, 0.001)
        assertEquals("", n.action)
        assertEquals(70f, decoded.hunger, 0.001f)
    }
}
