package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ConditionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.CreatureDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.GoalSeedDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.HouseholdDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.KinDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ScheduleDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ScheduleEntryDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Creature
import app.rork.p7buzvrzcw3frddi8y50ou.sim.CreatureState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.GoalState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.HouseholdSave
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Kin
import app.rork.p7buzvrzcw3frddi8y50ou.sim.KinEdge
import app.rork.p7buzvrzcw3frddi8y50ou.sim.MIN_PER_SEC
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcActions
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PackView
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PLAYER_ID
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveBinding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveStore
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SocialEvents
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.DialogueSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.DiscoverySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.GoalSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.HouseholdSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.InventorySystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.KinshipSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.NeedsSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.NpcSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.ProgressionSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.SnapshotSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.SocialSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.ScheduleSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.TimeSystem
import app.rork.p7buzvrzcw3frddi8y50ou.sim.systems.TradeSystem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Households, families and personal lives: roofs that are not kin, kin that is not a roof,
 * duties shared by character, children kept safe and unpaid, wishes pursued in free hours,
 * and a journal that shows only what the player could truly have heard. Everything runs on
 * the JVM against a small hand-authored world, through the production pathways.
 */
class FamilyTest {

    /** Two fishers, their child, a grandmother, a wife under another roof, and a dreamer. */
    private fun familyPack(): ContentPack = TestPacks.base().copy(
        professions = listOf(
            ProfessionDef(id = "fisher", name = "Fisher", workplace = "harbour", schedule = "worker_routine"),
            ProfessionDef(id = "villager", name = "Villager"),
            ProfessionDef(id = "dreamer", name = "Dreamer"),
        ),
        buildings = TestPacks.base().buildings + listOf(
            BuildingDef(id = "forge", name = "Forge", type = "workshop", x = 5f, z = 10f),
            BuildingDef(id = "bothy", name = "Bothy", type = "house", x = -20f, z = 30f),
        ),
        schedules = listOf(
            ScheduleDef(
                id = "worker_routine",
                name = "River days",
                entries = listOf(
                    ScheduleEntryDef(start = 21f, end = 6f, activity = "sleep", target = "home"),
                    ScheduleEntryDef(start = 6f, end = 14f, activity = "work", target = "work"),
                    ScheduleEntryDef(start = 14f, end = 16f, activity = "wander"),
                    ScheduleEntryDef(start = 16f, end = 20f, activity = "socialize"),
                    ScheduleEntryDef(start = 20f, end = 21f, activity = "eat", target = "home"),
                ),
            ),
            ScheduleDef(
                id = "child_routine",
                name = "Days underfoot",
                entries = listOf(
                    ScheduleEntryDef(start = 20f, end = 7f, activity = "sleep", target = "home"),
                    ScheduleEntryDef(start = 7f, end = 8f, activity = "eat", target = "home"),
                    ScheduleEntryDef(start = 8f, end = 9f, activity = "housework", target = "home"),
                    ScheduleEntryDef(start = 9f, end = 12f, activity = "wander"),
                    ScheduleEntryDef(start = 12f, end = 13f, activity = "eat", target = "home"),
                    ScheduleEntryDef(start = 13f, end = 16f, activity = "socialize"),
                    ScheduleEntryDef(start = 16f, end = 19f, activity = "wander"),
                    ScheduleEntryDef(start = 19f, end = 20f, activity = "eat", target = "home"),
                ),
            ),
            ScheduleDef(
                id = "odd_child",
                name = "A day no child should keep",
                entries = listOf(
                    ScheduleEntryDef(start = 8f, end = 10f, activity = "patrol", target = "work"),
                    ScheduleEntryDef(start = 10f, end = 12f, activity = "shop"),
                ),
            ),
        ),
        npcs = listOf(
            NpcDef(
                id = "hawk", name = "Hawk", profession = "fisher", age = 46, home = "cottage",
                goals = listOf(GoalSeedDef(type = "improve_skill", target = "foraging", priority = 40)),
                personality = mapOf(
                    "ambition" to 0.9f, "courage" to 0.8f, "sociability" to 0.4f,
                    "generosity" to 0.9f, "greed" to 0.1f, "loyalty" to 0.9f,
                ),
            ),
            NpcDef(
                id = "mira", name = "Mira", profession = "fisher", age = 44, home = "cottage",
                goals = listOf(GoalSeedDef(type = "support_household", priority = 45)),
                personality = mapOf(
                    "ambition" to 0.3f, "courage" to 0.6f, "sociability" to 0.7f,
                    "generosity" to 0.9f, "greed" to 0.1f, "loyalty" to 0.8f,
                ),
            ),
            NpcDef(
                id = "pip", name = "Pip", profession = "villager", age = 12, home = "cottage",
                schedule = "child_routine",
                personality = mapOf(
                    "ambition" to 0.4f, "courage" to 0.6f, "sociability" to 0.7f,
                    "generosity" to 0.7f, "greed" to 0.2f, "loyalty" to 0.8f,
                ),
            ),
            NpcDef(
                id = "gran", name = "Gran", profession = "villager", age = 68, home = "cottage",
                personality = mapOf(
                    "ambition" to 0.2f, "courage" to 0.5f, "sociability" to 0.3f,
                    "generosity" to 0.6f, "greed" to 0.3f, "loyalty" to 0.9f,
                ),
            ),
            NpcDef(
                id = "wren", name = "Wren", profession = "fisher", age = 40, home = "bothy",
                personality = mapOf(
                    "ambition" to 0.5f, "courage" to 0.5f, "sociability" to 0.5f,
                    "generosity" to 0.1f, "greed" to 0.9f, "loyalty" to 0.1f,
                ),
            ),
            NpcDef(
                id = "finn", name = "Finn", profession = "dreamer", age = 20, home = "cottage",
                goals = listOf(GoalSeedDef(type = "closer_to_relative", target = "wren", priority = 50)),
                personality = mapOf(
                    "ambition" to 0.2f, "courage" to 0.5f, "sociability" to 0.2f,
                    "generosity" to 0.5f, "greed" to 0.3f, "loyalty" to 0.2f,
                ),
            ),
        ),
        households = listOf(
            HouseholdDef(id = "hh_hawk", name = "Hawk's roof", home = "cottage", members = listOf("hawk", "mira", "pip", "gran"), head = "hawk", type = "family"),
            HouseholdDef(id = "hh_wren", name = "The Bothy", home = "bothy", members = listOf("wren"), head = "wren", type = "single"),
        ),
        kinship = listOf(
            KinDef(from = "hawk", to = "pip", kind = Kin.PARENT),
            KinDef(from = "mira", to = "pip", kind = Kin.PARENT),
            KinDef(from = "gran", to = "hawk", kind = Kin.PARENT),
            KinDef(from = "hawk", to = "wren", kind = Kin.SPOUSE),
            KinDef(from = "finn", to = "wren", kind = Kin.SIBLING),
        ),
        creatures = listOf(CreatureDef(id = "wolf", name = "Wolf", aggressive = true, territory = "vale", count = 1, damage = 5f)),
    )

    /** A running world, bound in the same order the game engine binds: save, life, kin, roofs, wishes. */
    private fun familyWorld(save: SaveGame = SaveGame(minutes = morning()), pack: ContentPack = familyPack()): World {
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
        w.kin = KinshipSystem(w)
        w.households = HouseholdSystem(w)
        w.goals = GoalSystem(w)
        w.kin.bind()
        w.households.bind()
        w.goals.bind()
        return w
    }

    /** Day 3, eight in the morning: work hours for the trades, free hours for the dreamer. */
    private fun morning() = 3 * 1440.0 + 8 * 60.0

    private fun World.id(name: String) = content.npcs.canonical(name)
    private fun World.person(name: String) = npcById[id(name)]!!
    private fun World.hawkId() = id("hawk")
    private fun World.wrenHh(): HouseholdSave = households.of(id("wren"))!!
    private fun World.hawkHh(): HouseholdSave = households.of(id("hawk"))!!
    private fun diagnosticsText() = Diagnostics.state.value.entries.joinToString(" ") { it.message }

    // ------------------------------------------------------------ household is not family

    @Test
    fun `spouses who live apart are one family under two roofs`() {
        val w = familyWorld()
        val h = w.hawkId(); val r = w.id("wren")

        assertTrue("the marriage is recorded", w.kin.spouseOf(h) == r)
        assertFalse("the husband's roof is not the wife's", w.households.of(h) === w.households.of(r))
        assertTrue("the wife keeps her own household", r !in w.households.of(h)!!.members)
        assertEquals(
            "the marriage is one bond, read both ways - the pack's word and the derived one",
            setOf("spouse", "partner"),
            setOf(w.kin.kinLabel(h, r), w.kin.kinLabel(r, h)),
        )
    }

    @Test
    fun `a pack's households are instantiated and everyone wakes under a roof`() {
        val w = familyWorld()

        for (n in w.npcs) {
            assertNotNull("no one is left roofless - ${n.def.id}", w.households.of(n.def.id))
        }
        val roof = w.hawkHh()
        assertEquals("the family of four, and Finn the dreamer lodging under the same roof", 5, roof.members.size)
        assertEquals("hawk", roof.head.substringAfterLast(':'))
        assertEquals("family", roof.type)
    }

    @Test
    fun `cohabitants without a written household become household but never family`() {
        // Two new folk share the forge as a home; the pack writes no household for them.
        val pack = familyPack().copy(
            npcs = familyPack().npcs + listOf(
                NpcDef(id = "gae", name = "Gae", profession = "dreamer", age = 30, home = "forge",
                    personality = mapOf("generosity" to 0.1f, "loyalty" to 0.1f, "greed" to 0.9f, "ambition" to 0.5f)),
                NpcDef(id = "hol", name = "Hol", profession = "dreamer", age = 30, home = "forge",
                    personality = mapOf("generosity" to 1.0f, "loyalty" to 1.0f, "greed" to 0.0f, "ambition" to 0.5f)),
            ),
        )
        val w = familyWorld(pack = pack)
        val gae = w.id("gae"); val hol = w.id("hol")

        val shared = w.households.of(gae)!!
        assertEquals("one shared roof for both", shared, w.households.of(hol))
        assertEquals("shared", shared.type)
        assertFalse("a shared roof is not a family bond", w.kin.related(gae, hol))
    }

    // ------------------------------------------------------------ membership

    @Test
    fun `moving house moves the person and both roofs remember it`() {
        val w = familyWorld()
        val finn = w.id("finn")
        val from = w.households.of(finn)!!
        val to = w.wrenHh()
        val changesBefore = w.households.membershipChanges

        assertTrue(w.households.move(finn, to.id))
        assertEquals(to.id, w.households.of(finn)!!.id)
        assertFalse(finn in from.members)
        assertTrue(from.history.any { it.text.contains("left the household") })
        assertTrue(w.households.membershipChanges > changesBefore)
        assertFalse("a stranger's roof is not found", w.households.move(finn, "no-such-household"))
    }

    @Test
    fun `an emptied household is kept, with its history`() {
        val w = familyWorld()
        val wrenHh = w.wrenHh()

        assertTrue(w.households.remove(w.id("wren"), "Walking out one morning,"))

        assertTrue("the household remains a fact of the vale", w.save.households.any { it === wrenHh })
        assertTrue(wrenHh.members.isEmpty())
        assertTrue("its headship is cleared with its people", wrenHh.head.isEmpty())
        assertTrue(wrenHh.history.any { it.text.contains("left the household") })
    }

    @Test
    fun `a second roof cannot be founded on housed people twice`() {
        val w = familyWorld()
        val gaeless = familyPack().copy(
            npcs = familyPack().npcs + NpcDef(id = "gae", name = "Gae", profession = "dreamer", age = 30, home = "forge"),
        )
        val w2 = familyWorld(pack = gaeless)
        val gae = w2.id("gae")
        assertNotNull("coverage housed the newcomer first", w2.households.of(gae))
        assertNull(w2.households.establish("hh_x", "X", "forge", setOf(gae), "gae", "single"))

        assertTrue(w2.households.remove(gae, "Testing,"))
        val made = w2.households.establish("hh_x", "X", "forge", setOf(gae), "gae", "single")
        assertNotNull(made)
        assertEquals(made, w2.households.of(gae))
        assertNull("the id is taken now", w2.households.establish("hh_x", "Y", "forge", setOf(gae), "", "single"))
    }

    @Test
    fun `a saved household naming a stranger lets them go with a report`() {
        val content = TestPacks.build(familyPack())
        val save = SaveGame(minutes = 1000.0)
        val h = content.npcs.canonical("hawk")
        save.households += HouseholdSave(id = "hh_bad", name = "Bad", home = "cottage", members = linkedSetOf(h, "ghost"), head = "ghost")

        val w = familyWorld(save = save)

        val hh = save.households.single { it.id == "hh_bad" }
        assertFalse("the ghost was let go", "ghost" in hh.members)
        assertTrue("the living member stayed", h in hh.members)
        assertTrue("a head who is not a member is no head", hh.head != "ghost" && (hh.head.isEmpty() || hh.head in hh.members))
        assertTrue("the drop was reported", diagnosticsText().contains("ghost"))
    }

    @Test
    fun `nobody lives under two roofs at once`() {
        val content = TestPacks.build(familyPack())
        val save = SaveGame(minutes = 1000.0)
        val h = content.npcs.canonical("hawk")
        // Two saved roofs both claim the same person; the first roof to hold them keeps them.
        save.households += HouseholdSave(id = "hh_a", name = "A", home = "cottage", members = linkedSetOf(h))
        save.households += HouseholdSave(id = "hh_b", name = "B", home = "cottage", members = linkedSetOf(h))

        familyWorld(save = save)

        assertEquals(1, save.households.count { h in it.members })
        assertTrue(save.households.first { h in it.members }.id.endsWith("hh_a"))
    }

    // ------------------------------------------------------------ kinship

    @Test
    fun `a bond is stored once and read both ways`() {
        val w = familyWorld()
        val h = w.hawkId(); val p = w.id("pip")
        val edgesBefore = w.save.kin.edges.size

        assertFalse("the same bond is not declared twice", w.kin.declare(h, Kin.PARENT, p))
        assertEquals("no duplicate was written", edgesBefore, w.save.kin.edges.size)
        assertEquals("the parent reads child", "child", w.kin.kinLabel(h, p))
        assertEquals("the child reads parent", "parent", w.kin.kinLabel(p, h))
        assertTrue(w.kin.parentsOf(p).contains(h))
        assertTrue(w.kin.childrenOf(h).contains(p))
        assertEquals("the derived word is not a bond one may declare", false, w.kin.declare(p, "child", h))
    }

    @Test
    fun `symmetric bonds collapse to one canonical record`() {
        val w = familyWorld()
        val h = w.hawkId(); val r = w.id("wren")

        assertFalse("the same marriage, said the other way round", w.kin.declare(r, Kin.SPOUSE, h))
        assertEquals(1, w.save.kin.edges.values.count { it.kind == Kin.SPOUSE })
        assertTrue(w.kin.siblingsOf(h).isEmpty())

        val mira = w.id("mira")
        assertTrue(w.kin.declare(w.id("gran"), Kin.SIBLING, mira))
        assertEquals("the pair is kept as one record", 1, w.save.kin.edges.values.count {
            it.kind == Kin.SIBLING && (it.from == mira || it.to == mira)
        })
    }

    @Test
    fun `a bond written the derived way round is flipped, not rejected`() {
        val content = TestPacks.build(familyPack())
        val save = SaveGame(minutes = 1000.0)
        val h = content.npcs.canonical("hawk")
        val p = content.npcs.canonical("pip")
        // The save wrote "Pip child of Hawk"; the truth is the same bond, said from the other end.
        save.kin.edges["${p}>$h:child"] = KinEdge(p, h, "child")

        familyWorld(save = save)

        val edges = save.kin.edges.values.filter { it.to == p || it.from == p }
        assertEquals(2, edges.size) // both parents, neither duplicated
        assertTrue(edges.all { it.kind == Kin.PARENT && it.from != p })
    }

    @Test
    fun `a bond in an unknown word is let go with a report`() {
        val content = TestPacks.build(familyPack())
        val save = SaveGame(minutes = 1000.0)
        val h = content.npcs.canonical("hawk")
        val r = content.npcs.canonical("wren")
        save.kin.edges["$h>$r:blood_brother"] = KinEdge(h, r, "blood_brother")

        familyWorld(save = save)

        assertFalse(save.kin.edges.values.any { it.kind == "blood_brother" })
        assertTrue(diagnosticsText().contains("blood_brother"))
    }

    @Test
    fun `unknown ancestry is no record at all, and is valid`() {
        val w = familyWorld()
        val finn = w.id("finn")

        assertNull("no invented parents", w.kin.edgeBetween(finn, "nobody"))
        assertTrue(w.kin.parentsOf(finn).isEmpty())
        assertNull(w.kin.kinLabel(finn, w.hawkId()))
        assertFalse(w.kin.related(finn, w.hawkId()))
        // A bond to the player may be declared at any time; the record is the only source.
        assertTrue(w.kin.declare(w.hawkId(), Kin.PARENT, PLAYER_ID))
        assertEquals("child", w.kin.kinLabel(w.hawkId(), PLAYER_ID))
    }

    @Test
    fun `adoption and guardianship are their own bonds`() {
        val w = familyWorld()
        val finn = w.id("finn")

        assertTrue(w.kin.declare(w.hawkId(), Kin.ADOPTIVE, finn))
        assertTrue(w.kin.declare(w.id("gran"), Kin.GUARDIAN, finn))

        assertTrue("an adopted child counts as theirs", w.kin.childrenOf(w.hawkId()).contains(finn))
        assertEquals(listOf(w.id("gran")), w.kin.guardiansOf(finn))
        assertEquals(listOf(finn), w.kin.wardsOf(w.id("gran")))
        assertFalse("a guardian is not a parent", w.kin.parentsOf(finn).contains(w.id("gran")))
    }

    @Test
    fun `family start out known to one another, and no memory was made of it`() {
        val w = familyWorld()
        val h = w.hawkId(); val p = w.id("pip")

        assertTrue(w.social.dim(h, p, "familiarity") >= 55f)
        assertTrue(w.social.dim(p, h, "familiarity") >= 55f)
        assertTrue("raised together is warmth", w.social.dim(p, h, "affection") >= 45f)
        assertTrue("raised together is trust", w.social.dim(p, h, "trust") >= 50f)
        assertTrue("family is not news", w.person("pip").s.experiences.isEmpty())
        assertTrue("and none of it is gossip", w.save.social.gossip.isEmpty())
    }

    // ------------------------------------------------------------ duties

    @Test
    fun `the work is shared out by character, and the child only lightly`() {
        val w = familyWorld()
        val hh = w.hawkHh()
        val p = w.id("pip")

        val holders = hh.duties.values.toSet()
        assertTrue("the work is not all one person's", holders.size >= 2)
        assertTrue(
            "the child is given only light work",
            hh.duties.entries.filter { it.value == p }.all { it.key in HouseholdSystem.LIGHT_DUTIES },
        )
        assertTrue("the child is not given the heavy work", hh.duties["cooking"] != p && hh.duties["repairs"] != p)

        val before = hh.duties.toMap()
        w.households.assignDuties(hh)
        assertEquals("the same people share out the same way, every time", before, hh.duties.toMap())
    }

    @Test
    fun `the generous take the pot before the greedy`() {
        val pack = familyPack().copy(
            npcs = familyPack().npcs + listOf(
                NpcDef(id = "gae", name = "Gae", profession = "dreamer", age = 30, home = "forge",
                    personality = mapOf("generosity" to 0.1f, "loyalty" to 0.1f, "greed" to 0.9f, "ambition" to 0.5f)),
                NpcDef(id = "hol", name = "Hol", profession = "dreamer", age = 30, home = "forge",
                    personality = mapOf("generosity" to 1.0f, "loyalty" to 1.0f, "greed" to 0.0f, "ambition" to 0.5f)),
            ),
        )
        val w = familyWorld(pack = pack)
        val shared = w.households.of(w.id("gae"))!!

        assertEquals("cooking falls to the generous one", w.id("hol"), shared.duties["cooking"])
    }

    // ------------------------------------------------------------ domestic effects

    @Test
    fun `cooking fills the larder and housework mends the house`() {
        val w = familyWorld()
        val hh = w.hawkHh()
        val hawk = w.person("hawk")

        hh.food = 20f
        w.households.applyAction(hawk, NpcActions.COOK, 5f)
        assertTrue("the larder filled", hh.food > 20f)

        hh.condition = 20f
        w.households.applyAction(hawk, NpcActions.HOUSEWORK, 5f)
        assertTrue("the house mended", hh.condition > 20f)
    }

    @Test
    fun `a thin larder makes a thin meal`() {
        val w = familyWorld()
        val hawk = w.person("hawk")
        val hh = w.hawkHh()

        fun meal(food: Float): Pair<Float, Float> {
            hh.food = food
            hawk.s.hunger = 50f
            hawk.s.action = NpcActions.EAT
            hawk.destName = hawk.homeName
            hawk.path.clear()
            hawk.pause = 0f
            w.npcSim.step(hawk, 2f, detailed = false)
            return hawk.s.hunger to hh.food
        }

        val (fedFull, larderFull) = meal(90f)
        val (fedThin, larderThin) = meal(10f)

        assertTrue("a full larder makes a full meal", fedFull > fedThin)
        assertTrue("a meal drains the larder", larderThin < 10f)
        assertEquals("the same meal costs the same either way", 90f - larderFull, 10f - larderThin, 0.01f)
    }

    @Test
    fun `a carer warms the little ones near them`() {
        val w = familyWorld()
        val gran = w.person("gran")
        val pip = w.person("pip")
        gran.s.action = NpcActions.CARE
        gran.path.clear()
        gran.pause = 0f
        gran.decideAt = w.save.minutes + 9999.0
        pip.s.social = 40f
        pip.s.safety = 40f
        pip.s.x = gran.s.x + 2f
        pip.s.z = gran.s.z

        repeat(20) { w.npcSim.step(gran, 2f, detailed = false) }

        assertTrue("the child was warmed", pip.s.social > 40f)
        assertTrue("the child was steadied", pip.s.safety > 40f)
    }

    // ------------------------------------------------------------ children

    @Test
    fun `the child keeps the child's day - housework at eight, playing at half nine`() {
        val w = familyWorld()
        val pip = w.person("pip")

        pip.decideAt = w.save.minutes - 1.0
        w.npcSim.step(pip, 0.05f, detailed = true)
        assertEquals(NpcActions.HOUSEWORK, pip.s.action)
        assertTrue(pip.destLabel.contains("Seeing to"))

        w.save.minutes = morning() + 90.0 // half past nine: the wander window
        pip.decideAt = w.save.minutes - 1.0
        w.npcSim.step(pip, 0.05f, detailed = true)
        assertEquals(NpcActions.WANDER, pip.s.action)
        assertEquals("playing close to home", pip.homeName, pip.destName)
        assertTrue(pip.destLabel.contains("Playing near home"))
    }

    @Test
    fun `no schedule sends a child on patrol or to the shops`() {
        val pack = familyPack().copy(npcs = familyPack().npcs.map {
            if (it.id == "pip") it.copy(schedule = "odd_child") else it
        })
        val w = familyWorld(pack = pack)
        val pip = w.person("pip")

        pip.decideAt = w.save.minutes - 1.0
        w.npcSim.step(pip, 0.05f, detailed = true)

        assertEquals("the beat became play", NpcActions.WANDER, pip.s.action)
        assertEquals(pip.homeName, pip.destName)
    }

    @Test
    fun `the little ones are not paid for their days`() {
        val w = familyWorld()
        val pip = w.person("pip")
        val hawk = w.person("hawk")
        for (n in listOf(pip, hawk)) {
            n.s.action = NpcActions.WORK
            n.path.clear()
            n.pause = 0f
            n.decideAt = w.save.minutes + 9999.0
        }

        val pipPurse = pip.s.gold
        repeat(50) {
            w.npcSim.step(pip, 2f, detailed = false)
            w.npcSim.step(hawk, 2f, detailed = false)
        }

        assertEquals("a child earns nothing", pipPurse, pip.s.gold)
        assertTrue("the grown work still pays", hawk.s.gold > hawk.def.gold)
    }

    // ------------------------------------------------------------ wishes

    @Test
    fun `wishes are written by packs, and grown from character where none is written`() {
        val w = familyWorld()
        val hawk = w.person("hawk"); val mira = w.person("mira"); val pip = w.person("pip"); val gran = w.person("gran")

        assertEquals("the pack's wish, at the pack's pressing", 40, w.goals.suggest(hawk)!!.priority)
        assertEquals("improve_skill", w.goals.suggest(hawk)!!.type)
        assertEquals(45, w.goals.suggest(mira)!!.priority)
        assertNull("a child carries no wish yet", w.goals.suggest(pip))
        val granGoal = w.goals.suggest(gran)!!
        assertEquals("a wish grown from loyalty to a full house", "support_household", granGoal.type)
        assertEquals("a grown wish presses less than a written one", GoalSystem.GENERATED_PRIORITY, granGoal.priority)
    }

    @Test
    fun `hours of work advance the wish, and finishing it is written down`() {
        val w = familyWorld()
        val hawk = w.person("hawk")
        val mira = w.person("mira")
        val g = w.goals.suggest(hawk)!!

        w.goals.progress(hawk, NpcActions.WORK, 30f)
        assertEquals("the first stretch advanced it", 62.7f, g.progress, 1.5f)
        w.goals.progress(hawk, NpcActions.WORK, 30f)

        assertEquals(GoalState.DONE, g.status)
        assertTrue(g.doneAt > 0.0)
        assertTrue("the chronicle tells it", w.save.history.any { it.text.contains("sharpened") })
        assertTrue("the household remembers it", mira.s.experiences.any { it.type == SocialEvents.GOAL_COMPLETED })
        assertEquals(1, w.goals.goalsCompleted)
    }

    @Test
    fun `the wish is pursued only in free hours - duty, then the wish`() {
        val w = familyWorld()
        val hawk = w.person("hawk")
        val hh = w.hawkHh()
        hh.food = 30f // the cooking duty calls
        hawk.decideAt = w.save.minutes - 1.0

        w.npcSim.step(hawk, 0.05f, detailed = true)

        assertEquals("the day's schedule outranks the waiting work", NpcActions.WORK, hawk.s.action)

        val finn = w.person("finn") // the dreamer keeps no schedule: free hours
        finn.decideAt = w.save.minutes - 1.0
        w.npcSim.step(finn, 0.05f, detailed = true)
        assertEquals("the wish sends him visiting", NpcActions.VISIT, finn.s.action)
        assertEquals("to his sister's roof", "Bothy", finn.destName)
    }

    @Test
    fun `critical needs outrank the wish`() {
        val w = familyWorld()
        val finn = w.person("finn")
        finn.s.hunger = 10f
        finn.decideAt = w.save.minutes - 1.0

        w.npcSim.step(finn, 0.05f, detailed = true)

        assertEquals(NpcActions.EAT, finn.s.action)
    }

    @Test
    fun `danger outranks everything the day had planned`() {
        val w = familyWorld()
        val finn = w.person("finn")
        finn.decideAt = w.save.minutes - 1.0
        w.npcSim.step(finn, 0.05f, detailed = true)
        assertEquals(NpcActions.VISIT, finn.s.action)

        val wolfDef = w.content.creatures[w.content.creatures.canonical("wolf")]!!
        w.creatures += Creature(wolfDef, CreatureState("wolf#0", "wolf", x = finn.s.x + 3f, z = finn.s.z, health = 10f))
        finn.decideAt = w.save.minutes - 1.0
        w.npcSim.step(finn, 0.05f, detailed = true)

        assertEquals(NpcActions.FLEE, finn.s.action)
    }

    // ------------------------------------------------------------ binding and persistence

    @Test
    fun `a world saved before households existed wakes with roofs, bonds and wishes`() {
        val w = familyWorld() // a plain SaveGame: no households, no kin, no goals

        assertEquals("the pack's roof was instantiated, and the dreamer lodged with them", 5, w.hawkHh().members.size)
        assertNotNull("the marriage was instantiated", w.kin.spouseOf(w.hawkId()))
        assertTrue("the family know each other", w.social.dim(w.hawkId(), w.id("pip"), "familiarity") >= 55f)
        assertEquals("the written wish was seeded", "improve_skill", w.goals.suggest(w.person("hawk"))!!.type)
    }

    @Test
    fun `roofs, bonds and wishes survive a save and reload`() {
        val w = familyWorld()
        val finn = w.id("finn")
        w.households.move(finn, w.wrenHh().id)
        w.goals.progress(w.person("hawk"), NpcActions.WORK, 30f)

        val reloaded = SaveStore.decode(SaveStore.encode(w.save))

        assertEquals(w.save.households, reloaded.households)
        assertEquals(w.save.kin, reloaded.kin)
        assertEquals(w.person("hawk").s.goals, reloaded.npcs.single { it.id == w.hawkId() }.goals)
        assertEquals("a moved person stays moved", w.wrenHh().id, reloaded.households.first { finn in it.members }.id)
    }

    @Test
    fun `a distant wife's household eats from her larder all the same`() {
        val w = familyWorld()
        val wren = w.person("wren")
        val now = morning() + 750.0 // half past eight in the evening: supper hours
        w.save.minutes = now
        wren.s.x = 400f
        wren.s.z = 400f
        wren.s.lastSim = now - 120.0
        val hh = w.wrenHh()
        hh.food = 70f

        w.npcSim.catchUp(wren, now)

        assertTrue("supper was had, and paid for from the larder", hh.food < 70f)
        assertEquals(now, wren.s.lastSim, 0.001)
    }

    @Test
    fun `a family lives the same day twice, the same way`() {
        fun run(): World {
            val w = familyWorld()
            val hawk = w.person("hawk"); val pip = w.person("pip"); val finn = w.person("finn"); val wren = w.person("wren")
            repeat(40) {
                w.save.minutes += 2.0 * MIN_PER_SEC
                for (n in listOf(hawk, pip, finn)) w.npcSim.step(n, 2f, detailed = false)
            }
            wren.s.x = 300f; wren.s.z = 300f; wren.s.lastSim = w.save.minutes - 100.0
            w.npcSim.catchUp(wren, w.save.minutes + 100.0)
            w.goals.progress(hawk, NpcActions.WORK, 20f)
            w.households.move(w.id("finn"), w.wrenHh().id)
            return w
        }
        val a = run(); val b = run()

        assertEquals("the same roofs, the same history", a.save.households, b.save.households)
        assertEquals("the same family", a.save.kin, b.save.kin)
        assertEquals(
            "the same wishes, advanced the same hours",
            a.save.npcs.map { it.goals.toList() },
            b.save.npcs.map { it.goals.toList() },
        )
    }

    // ------------------------------------------------------------ dialogue and the journal

    @Test
    fun `a pack line can be kept for family alone, and for the ambitious`() {
        val w = familyWorld()
        val hawk = w.person("hawk")

        w.kin.declare(w.hawkId(), Kin.PARENT, PLAYER_ID)
        assertEquals("kin to the player are greeted as family", "family", w.dialogue.relationOf(hawk))
        assertTrue("any bond will do", w.dialogue.holds(ConditionDef(type = "kin", key = ""), hawk))
        assertTrue("the word from his end is child", w.dialogue.holds(ConditionDef(type = "kin", key = "child"), hawk))
        assertFalse(w.dialogue.holds(ConditionDef(type = "kin", key = "parent"), hawk))

        assertTrue(w.dialogue.holds(ConditionDef(type = "goal", key = "improve_skill"), hawk))
        assertFalse(w.dialogue.holds(ConditionDef(type = "goal", key = "save_gold"), hawk))
    }

    @Test
    fun `the journal shows a roof only once the player has met someone under it`() {
        val w = familyWorld()
        val journal = MutableStateFlow(app.rork.p7buzvrzcw3frddi8y50ou.sim.JournalView())
        val snap = SnapshotSystem(w, MutableStateFlow(app.rork.p7buzvrzcw3frddi8y50ou.sim.Hud()), MutableStateFlow(0f), MutableStateFlow(PackView()), journal)
        val p = w.id("pip")

        w.journalDirty = true
        snap.publish(1f, force = true)
        var hh = journal.value.households.first { it.members.isNotEmpty() && it.type == "family" }
        assertFalse("an unmet roof is not named", hh.known)
        assertEquals("A household you have yet to meet", hh.name)
        assertTrue(hh.members.all { it == "a face you do not know" })
        assertTrue("no troubles are told of the unmet", hh.history.isEmpty())

        // The player comes to know the child; the roof, its names and its troubles follow.
        w.social.adjust(p, PLAYER_ID, "familiarity", 30f)
        w.households.note(w.hawkHh(), "The larder was mended.")
        w.journalDirty = true
        snap.publish(1f, force = true)
        hh = journal.value.households.first { it.known }
        assertEquals("Hawk's roof", hh.name)
        assertTrue(hh.members.contains("Pip"))
        assertTrue(hh.history.any { it.contains("larder") })

        // Kin are named only for kin the player also knows; wishes only for a friend.
        val hawkView = journal.value.npcs.first { it.id == w.hawkId() }
        assertTrue("an unknown child is not named to the player", hawkView.family.isEmpty())
        w.social.adjust(p, PLAYER_ID, "familiarity", 30f)
        w.social.adjust(w.hawkId(), PLAYER_ID, "familiarity", 30f)
        w.social.adjust(w.hawkId(), PLAYER_ID, "friendship", 45f)
        w.journalDirty = true
        snap.publish(1f, force = true)
        val hawkAgain = journal.value.npcs.first { it.id == w.hawkId() }
        assertTrue(hawkAgain.family.any { it.startsWith("Pip") })
        assertTrue("a friend hears the wish", hawkAgain.goals.isNotEmpty())
        assertTrue(hawkAgain.lifeStage.isNotBlank())
    }
}
