package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentPack
import app.rork.p7buzvrzcw3frddi8y50ou.data.NpcDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ProfessionDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.GossipKnowledge
import app.rork.p7buzvrzcw3frddi8y50ou.sim.InputState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.MemoryEntry
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcActions
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PLAYER_ID
import app.rork.p7buzvrzcw3frddi8y50ou.sim.RelationshipState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveBinding
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveGame
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SaveStore
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SocialEvents
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import app.rork.p7buzvrzcw3frddi8y50ou.sim.legacyAffinity
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The social world: directional opinions, structured memories that fade, gossip that frays with
 * each telling, and the player's honest place in it. Everything runs on the JVM against a small
 * hand-authored world, deterministically.
 */
class SocialTest {

    /** Two fishers with opposed temperaments, so personality factors can be seen. */
    private fun socialPack(): ContentPack = TestPacks.base().copy(
        professions = listOf(
            ProfessionDef(id = "fisher", name = "Fisher", workplace = "harbour"),
        ),
        npcs = listOf(
            NpcDef(
                id = "hawk", name = "Hawk", profession = "fisher", home = "cottage",
                personality = mapOf("generosity" to 1.0f, "sociability" to 0.9f, "loyalty" to 0.9f, "courage" to 0.9f, "aggression" to 0.1f),
            ),
            NpcDef(
                id = "wren", name = "Wren", profession = "fisher", home = "cottage",
                personality = mapOf("generosity" to 0.0f, "sociability" to 0.1f, "loyalty" to 0.0f, "courage" to 0.1f, "aggression" to 0.9f),
            ),
            NpcDef(
                id = "finn", name = "Finn", profession = "fisher", home = "cottage",
                personality = mapOf("generosity" to 0.5f, "sociability" to 1.0f, "loyalty" to 1.0f, "courage" to 0.9f, "aggression" to 0.1f),
            ),
        ),
    )

    private fun socialWorld(save: SaveGame = SaveGame(minutes = 3 * 1440.0 + 8 * 60.0), pack: ContentPack = socialPack()): World {
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

    private fun World.hawk() = npcById[content.npcs.canonical("hawk")]!!
    private fun World.wren() = npcById[content.npcs.canonical("wren")]!!
    private fun World.finn() = npcById[content.npcs.canonical("finn")]!!
    private fun World.hawkId() = content.npcs.canonical("hawk")
    private fun World.wrenId() = content.npcs.canonical("wren")
    private fun World.finnId() = content.npcs.canonical("finn")

    // ------------------------------------------------------------ relationships

    @Test
    fun `an opinion is directional, so what Hawk thinks of Wren says nothing of her thoughts of him`() {
        val w = socialWorld()
        val h = w.hawkId(); val r = w.wrenId()

        w.social.adjust(h, r, "friendship", 20f)

        assertEquals(20f, w.social.dim(h, r, "friendship"), 0.01f)
        assertEquals("her copy starts untouched", 0f, w.social.dim(r, h, "friendship"), 0.01f)
        assertNotEquals(w.social.rel(h, r), w.social.rel(r, h))
    }

    @Test
    fun `a person holds no opinion of themselves`() {
        val w = socialWorld()
        val h = w.hawkId()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { w.social.rel(h, h) }
    }

    @Test
    fun `the same event moves the forgiving less than the unforgiving`() {
        val w = socialWorld()
        val h = w.hawkId(); val r = w.wrenId()
        val forgiving = w.social.dim(h, r, "resentment")

        // Wren insults Hawk; Hawk is forgiving (generosity 1.0), and would be the less moved.
        w.social.event(SocialEvents.INSULT, listOf(r, h), -1, 50f)

        val hawkResentment = w.social.dim(h, r, "resentment")
        val wrenResentment = w.social.dim(r, h, "resentment")
        assertTrue("Hawk (generous) resents less than Wren (spiteful)", hawkResentment < wrenResentment)
        assertTrue("an insult is felt", wrenResentment > forgiving)
    }

    @Test
    fun `dimensions move independently`() {
        val w = socialWorld()
        val h = w.hawkId(); val r = w.wrenId()

        w.social.adjust(h, r, "trust", 30f)

        assertEquals(30f, w.social.dim(h, r, "trust"), 0.01f)
        assertEquals("friendship untouched by trust", 0f, w.social.dim(h, r, "friendship"), 0.01f)
        assertEquals("fear untouched by trust", 0f, w.social.dim(h, r, "fear"), 0.01f)
    }

    // ------------------------------------------------------------ memories

    @Test
    fun `memories fade with the years, but a rescue never falls out of a life`() {
        val w = socialWorld()
        val now = w.save.minutes

        w.social.playerRescue(w.wren(), "wolf")
        val rescue = w.wren().s.experiences.single()
        val smallTalk = w.social.event(SocialEvents.CONVERSATION, listOf(w.wrenId(), PLAYER_ID), 1, 14f)!!

        val aMonthOn = now + 30 * 1440.0
        assertTrue("a rescue is felt for weeks", w.social.effective(rescue, aMonthOn) > MemoryEntry.HISTORICAL_SIGNIFICANCE - 1f)
        assertTrue("small talk fades away", w.social.effective(smallTalk, aMonthOn) < 2f)
        val aYearOn = now + 360 * 1440.0
        assertTrue("a rescue is history forever", w.social.effective(rescue, aYearOn) >= MemoryEntry.HISTORICAL_SIGNIFICANCE)
        assertTrue("small talk is gone in a year", w.social.effective(smallTalk, aYearOn) < 0.5f)
    }

    @Test
    fun `the same event twice in a day is remembered once`() {
        val w = socialWorld()
        val wren = w.wren()

        w.social.playerTalk(wren, first = true)
        w.social.playerTalk(wren, first = true)

        val met = w.wren().s.experiences.filter { it.type == SocialEvents.CONVERSATION }
        assertEquals("one meeting, one memory", 1, met.size)
    }

    @Test
    fun `a life keeps only so many memories, and the weightiest survive the cut`() {
        val w = socialWorld()
        val wren = w.wren()

        w.social.playerRescue(wren, "wolf") // significance 75, protected forever
        for (day in 1..30) {
            w.save.minutes += 1500.0
            w.social.event(SocialEvents.CONVERSATION, listOf(w.wrenId(), w.hawkId()), 1, 10f)
        }

        assertTrue("the store stays finite", w.wren().s.experiences.size <= SocialSystem.MAX_MEMORIES)
        assertTrue(
            "the rescue survives thirty small days",
            w.wren().s.experiences.any { it.type == SocialEvents.RESCUE && PLAYER_ID in it.others },
        )
        assertTrue("the pruning was real", w.social.memoriesPruned > 0)
    }

    // ------------------------------------------------------------ encounters and cooldowns

    @Test
    fun `a pair that has just met does not meet again too soon`() {
        val w = socialWorld()
        val hawk = w.hawk(); val wren = w.wren()

        w.social.encounter(hawk, wren)
        assertEquals(1, w.social.socialCompleted)
        w.social.encounter(hawk, wren)
        assertEquals("the second try is skipped, not lost", 1, w.social.socialSkipped)
        assertEquals(1, w.social.socialCompleted)
        // Time enough passes, and they may meet again.
        w.save.minutes += SocialSystem.COOLDOWN_MINUTES + 1.0
        w.social.encounter(hawk, wren)
        assertEquals(2, w.social.socialCompleted)
    }

    @Test
    fun `working side by side builds familiarity and trust`() {
        val w = socialWorld()
        val h = w.hawkId(); val r = w.wrenId()

        w.social.sharedWork(w.hawk(), w.wren())

        assertTrue("familiarity grows in shared work", w.social.dim(h, r, "familiarity") > 0f)
        assertTrue("so does trust", w.social.dim(h, r, "trust") > 0f)
        // And it was met: the pair is on cooldown.
        w.social.sharedWork(w.hawk(), w.wren())
        assertEquals(1, w.social.socialSkipped)
    }

    // ------------------------------------------------------------ gossip

    @Test
    fun `gossip frays with each telling but never past its bounds, and stays the same story`() {
        val w = socialWorld()
        val hawk = w.hawk(); val wren = w.wren()
        val h = w.hawkId(); val r = w.wrenId()

        w.social.startStory("e1", SocialEvents.RESCUE, PLAYER_ID, h, "The traveller saved Wren from a wolf", importance = 60f)
        val story = w.save.social.gossip.single()
        assertEquals("the teller holds it first", setOf(h), story.holders.keys)

        // Deep trust and a heavy story make the telling certain, so the test stays deterministic.
        w.social.adjust(h, r, "trust", 100f)
        assertTrue("a story worth telling gets told", w.social.shareGossip(hawk, wren))
        val heard = story.holders[r]!!
        val told = story.holders[h]!!
        assertTrue("each telling is a little less sure", heard.confidence < told.confidence)
        assertTrue("and a little more bent", heard.distortion > told.distortion)
        assertTrue("but never past the bounds", heard.distortion in 0f..1f && heard.confidence in 0f..1f)
        assertTrue("the origin is kept identifiable", story.eventRef == "e1")
        assertTrue(w.social.knows(r, SocialEvents.RESCUE))
        assertFalse("the player was not there", w.social.knows(PLAYER_ID, SocialEvents.RESCUE))
    }

    @Test
    fun `a story is told no worse than it was heard, and the wording follows the telling`() {
        val w = socialWorld()
        val h = w.hawkId()
        w.social.startStory("e2", SocialEvents.RESCUE, PLAYER_ID, h, "The traveller saved Wren from a wolf", importance = 60f)
        val story = w.save.social.gossip.single()

        assertEquals(
            "a fresh telling is plain",
            "The traveller saved Wren from a wolf",
            w.social.storyText(story, h),
        )

        story.holders[h]!!.distortion = 0.9f
        assertTrue(
            "a much-told story grows a warning",
            w.social.storyText(story, h).contains("They say"),
        )

        story.holders[PLAYER_ID] = GossipKnowledge(
            minute = w.save.minutes, confidence = 0.7f, distortion = 0.5f,
        )
        assertTrue(
            "a twice-told story is hedged",
            w.social.storyText(story, PLAYER_ID).contains("or so it is said"),
        )
    }

    @Test
    fun `the player only knows what they have been told`() {
        val w = socialWorld()
        val hawk = w.hawk()
        val h = w.hawkId()

        w.social.startStory("e3", SocialEvents.BETRAYAL, w.wrenId(), h, "Wren broke a promise at the harbour", importance = 55f)
        assertTrue("nothing heard yet", w.social.playerStories().isEmpty())

        w.social.hearStory(w.save.social.gossip.single(), hawk)

        val heard = w.social.playerStories()
        assertEquals(1, heard.size)
        val (story, knowledge) = heard.single()
        assertEquals("e3", story.eventRef)
        assertTrue("what the player knows is a little less sure than the teller's own", knowledge.confidence < story.holders[h]!!.confidence)
        assertEquals("they know who told them", h, knowledge.via)
    }

    // ------------------------------------------------------------ the player's part

    @Test
    fun `a rescue is remembered warmly, structurally, and for a long time`() {
        val w = socialWorld()
        val r = w.wrenId()

        w.social.playerRescue(w.wren(), "wolf")

        val memory = w.wren().s.experiences.single()
        assertEquals(SocialEvents.RESCUE, memory.type)
        assertTrue(PLAYER_ID in memory.others)
        assertTrue("they saw it themselves", memory.witnessed)
        assertTrue("the beast is named in the telling", memory.text.contains("wolf"))
        assertTrue("trust grows", w.social.dim(r, PLAYER_ID, "trust") > 0f)
        assertTrue("so does affection", w.social.dim(r, PLAYER_ID, "affection") > 0f)
        assertTrue("so does respect", w.social.dim(r, PLAYER_ID, "respect") > 0f)
    }

    @Test
    fun `trading builds a merchant's trust a little at a time`() {
        val w = socialWorld()
        val r = w.wrenId()

        w.social.playerTrade(w.wren())
        w.social.playerTrade(w.wren())

        assertTrue(w.social.dim(r, PLAYER_ID, "familiarity") > 0f)
        assertTrue(w.social.dim(r, PLAYER_ID, "trust") > 0f)
    }

    @Test
    fun `turning points are written to the chronicle for the player to find`() {
        val w = socialWorld()
        val h = w.hawkId(); val r = w.wrenId()

        // A long friendship is grown and then a grudge driven deep.
        repeat(12) {
            w.social.adjust(h, r, "friendship", 6f)
        }
        assertTrue("a chronicle line marks the friendship", w.save.history.any { it.text.contains("fast friends") })

        repeat(10) {
            w.social.adjust(h, r, "hostility", 6f)
        }
        assertTrue("a chronicle line marks the falling out", w.save.history.any { it.text.contains("fallen out") })
    }

    // ------------------------------------------------------------ events become stories

    @Test
    fun `a real rescue becomes a story on the witness's lips`() {
        val w = socialWorld()
        // The same entry CombatSystem calls when the player brings down a beast.
        w.social.playerRescue(w.wren(), "wolf")

        val story = w.save.social.gossip.single()
        assertEquals(SocialEvents.RESCUE, story.category)
        assertEquals("the tale is about the one who did the deed", PLAYER_ID, story.subject)
        assertEquals("and it begins with the one who saw it", w.wrenId(), story.source)
        assertTrue("the beast is in the tale", story.text.contains("wolf"))
        assertTrue(
            "the tale traces back to the witness's own memory of it",
            w.wren().s.experiences.any { it.id == story.eventRef },
        )
    }

    @Test
    fun `an event nobody witnessed starts no story`() {
        val w = socialWorld()
        w.social.event(SocialEvents.RESCUE, listOf(w.wrenId(), PLAYER_ID), 1, 75f, witnessedBy = listOf(PLAYER_ID))
        assertTrue("no teller, no tale", w.save.social.gossip.isEmpty())
    }

    @Test
    fun `small talk stays small and starts no story`() {
        val w = socialWorld()
        w.social.event(SocialEvents.CONVERSATION, listOf(w.wrenId(), w.hawkId()), 1, 12f)
        assertTrue(w.save.social.gossip.isEmpty())
    }

    @Test
    fun `the same deed twice in a day starts one story, not two`() {
        val w = socialWorld()
        w.social.playerRescue(w.wren(), "wolf")
        w.save.minutes += 120.0
        w.social.playerRescue(w.wren(), "wolf")
        assertEquals(1, w.save.social.gossip.size)
    }

    @Test
    fun `many witnesses of one deed make one story, not a story each`() {
        val w = socialWorld()
        w.social.event(
            SocialEvents.BRAVERY_WITNESSED, listOf(w.wrenId(), PLAYER_ID), 1, 40f,
            witnessedBy = listOf(w.wrenId(), w.hawkId()),
        )
        val story = w.save.social.gossip.single()
        assertEquals(w.wrenId(), story.source)
        for (witness in listOf(w.wrenId(), w.hawkId())) {
            assertTrue(
                "both witnesses keep their own memory of it",
                w.save.npcs.first { it.id == witness }.experiences.any { it.type == SocialEvents.BRAVERY_WITNESSED },
            )
        }
    }

    @Test
    fun `a story loses confidence and gains bending with every hop, but stays the same story`() {
        val w = socialWorld()
        val h = w.hawkId(); val r = w.wrenId(); val f = w.finnId()
        w.social.startStory("e9", SocialEvents.RESCUE, PLAYER_ID, h, "The traveller saved Wren from a wolf", importance = 95f)
        val story = w.save.social.gossip.single()
        // Deep trust makes the tellings certain, so only the hops are under test.
        w.social.adjust(h, r, "trust", 100f)
        w.social.adjust(r, f, "trust", 100f)

        assertTrue(w.social.shareGossip(w.hawk(), w.wren()))
        val firstHop = story.holders[r]!!
        assertTrue(w.social.shareGossip(w.wren(), w.finn()))
        val secondHop = story.holders[f]!!

        assertTrue("two hearths, less certainty", secondHop.confidence < firstHop.confidence)
        assertTrue("two hearths, more bending", secondHop.distortion > firstHop.distortion)
        assertEquals("the origin survives the journey", "e9", story.eventRef)
    }

    @Test
    fun `an unwilling teller refuses far more often than they tell`() {
        val w = socialWorld()
        val wren = w.wren()
        for (i in 1..20) {
            w.social.startStory("e$i", SocialEvents.RESCUE, PLAYER_ID, w.wrenId(), "Tale number $i", importance = 0f)
        }
        var told = 0
        repeat(20) { if (w.social.shareGossip(wren, w.hawk())) told++ }
        assertTrue("refusals were counted", w.social.gossipRejected > 0)
        assertTrue("a dull story from a cold acquaintance hardly ever travels", told < 10)
    }

    @Test
    fun `a distant life's socialising stays bounded`() {
        val w = socialWorld()
        val finn = w.finn()
        w.social.adjust(w.finnId(), w.hawkId(), "familiarity", 20f) // they know of each other
        repeat(150) {
            w.social.abstractLife(finn, NpcActions.SOCIAL, 10f)
            w.save.minutes += 200.0
        }
        assertTrue("the village does not drown in tales", w.save.social.gossip.size <= SocialSystem.MAX_STORIES)
        assertTrue("a distant life keeps a finite memory", w.finn().s.experiences.size <= SocialSystem.MAX_MEMORIES)
        assertTrue("the meetings were real but bounded", w.social.socialCompleted <= 150)
    }

    @Test
    fun `a distant life keeps company with a workmate, chosen without scanning the village`() {
        val w = socialWorld()
        val finn = w.finn()
        repeat(30) {
            w.social.abstractLife(finn, NpcActions.WORK, 12f)
            w.save.minutes += 100.0
        }
        val mates = w.save.social.relationships.values
            .filter { it.holder == w.finnId() && it.familiarity > 0f }
            .map { it.other }
        assertTrue("the work was shared with somebody at the harbour", mates.isNotEmpty())
        assertTrue(
            "nobody outside the workplace was fetched",
            mates.all { it == w.hawkId() || it == w.wrenId() },
        )
    }

    @Test
    fun `looking for an acquaintance manufactures no blank opinions`() {
        val w = socialWorld()
        val before = w.save.social.relationships.size
        w.social.abstractLife(w.finn(), NpcActions.SOCIAL, 10f) // no acquaintances yet
        assertEquals("the village was not surveyed into opinions", before, w.save.social.relationships.size)
    }

    // ------------------------------------------------------------ bounded memory

    @Test
    fun `even a life of only great deeds stays within bounds, because deeds consolidate`() {
        val w = socialWorld()
        val wren = w.wren()
        for (day in 1..40) {
            w.save.minutes += 1500.0
            w.social.playerRescue(wren, "wolf") // every one protected, so pruning alone cannot help
        }
        assertTrue("the store stays finite however great the deeds", wren.s.experiences.size <= SocialSystem.MAX_MEMORIES)
        assertTrue(
            "the past was condensed, not discarded in silence",
            wren.s.experiences.any { it.text.contains("long history") },
        )
        assertTrue("nothing but rescues was ever known", wren.s.experiences.all { it.type == SocialEvents.RESCUE })
        assertTrue(w.social.memoriesPruned > 0)
    }

    @Test
    fun `a capped life survives a save and reload with its history intact`() {
        val w = socialWorld()
        val wren = w.wren()
        for (day in 1..30) {
            w.save.minutes += 1500.0
            w.social.playerRescue(wren, "wolf")
        }
        assertTrue(wren.s.experiences.size <= SocialSystem.MAX_MEMORIES)

        val reloaded = SaveStore.decode(SaveStore.encode(w.save))
        val again = reloaded.npcs.single { it.id == w.wrenId() }
        assertEquals(wren.s.experiences, again.experiences)
        assertTrue(again.experiences.size <= SocialSystem.MAX_MEMORIES)
    }

    // ------------------------------------------------------------ chronicle milestones

    @Test
    fun `a chronicle milestone is noted once, however often the line is crossed again`() {
        val w = socialWorld()
        val h = w.hawkId(); val r = w.wrenId()
        fun fastFriends() = w.save.history.count { it.text.contains("fast friends") }

        repeat(12) { w.social.adjust(h, r, "friendship", 6f) }
        assertEquals(1, fastFriends())
        // The friendship frays and mends; the chronicle does not tell the same news twice.
        w.social.adjust(h, r, "friendship", -40f)
        w.social.adjust(h, r, "friendship", 40f)
        assertEquals(1, fastFriends())
    }

    @Test
    fun `a chronicle milestone survives a save and reload`() {
        val w = socialWorld()
        repeat(12) { w.social.adjust(w.hawkId(), w.wrenId(), "friendship", 6f) }
        assertTrue(w.save.social.milestones.contains("${w.hawkId()}>${w.wrenId()}:friend"))

        val reloaded = SaveStore.decode(SaveStore.encode(w.save))
        assertTrue(
            "the noted milestone travelled with the world",
            reloaded.social.milestones.contains("${w.hawkId()}>${w.wrenId()}:friend"),
        )
    }

    @Test
    fun `milestones that name a stranger are rewritten like every other opinion`() {
        val content = TestPacks.build(socialPack())
        val save = SaveGame(minutes = 1000.0)
        val h = content.npcs.canonical("hawk"); val r = content.npcs.canonical("wren")
        save.social.milestones.add("$h>$r:friend")
        save.social.milestones.add("$h>ghost:rivals")

        SaveBinding.bind(save, content)

        assertTrue("the real milestone survives", save.social.milestones.contains("$h>$r:friend"))
        assertFalse("a milestone about nobody is let go", save.social.milestones.any { it.contains("ghost") })
    }

    // ------------------------------------------------------------ one authoritative relationship system

    @Test
    fun `the journal's number is a mirror of the social world, never a second ledger`() {
        val w = socialWorld()
        val wren = w.wren()
        val r = w.social.rel(w.wrenId(), PLAYER_ID)

        w.dialogue.warm(wren, 20)
        assertEquals(legacyAffinity(r), wren.s.affinity)
        w.dialogue.warm(wren, -30)
        assertEquals("a cooling is mirrored just as exactly", legacyAffinity(r), wren.s.affinity)
    }

    @Test
    fun `dialogue greets by the social world's measure, not by an old number`() {
        val w = socialWorld()
        val wren = w.wren()
        w.social.adjust(w.wrenId(), PLAYER_ID, "friendship", 60f)
        assertEquals("real friendship is greeted as friendship", "friend", w.dialogue.relationOf(wren))

        w.social.adjust(w.wrenId(), PLAYER_ID, "friendship", -60f)
        w.social.adjust(w.wrenId(), PLAYER_ID, "resentment", 60f)
        assertEquals("a grudge is greeted as rivalry", "rival", w.dialogue.relationOf(wren))
    }

    @Test
    fun `a loaded save's affinities are brought into agreement with its opinions`() {
        val content = TestPacks.build(socialPack())
        val save = SaveGame(minutes = 1000.0)
        val h = content.npcs.canonical("hawk")
        save.social.relationships["$h>$PLAYER_ID"] = RelationshipState(holder = h, other = PLAYER_ID, friendship = 80f, familiarity = 50f)
        save.npcs += NpcState(id = h, affinity = 5)

        SaveBinding.bind(save, content)

        val mirror = legacyAffinity(save.social.relationships["$h>$PLAYER_ID"]!!)
        assertTrue("the opinion won the argument over the stale number", mirror > 5)
        assertEquals(mirror, save.npcs.single { it.id == h }.affinity)
    }

    // ------------------------------------------------------------ binding

    @Test
    fun `binding drops opinions that name no one real, with a report, never in silence`() {
        val content = TestPacks.build(socialPack())
        val save = SaveGame(minutes = 1000.0)
        val h = content.npcs.canonical("hawk")
        val r = content.npcs.canonical("wren")
        save.social.relationships["$h>$r"] = app.rork.p7buzvrzcw3frddi8y50ou.sim.RelationshipState(holder = h, other = r, friendship = 40f)
        save.social.relationships["$h>ghost"] = app.rork.p7buzvrzcw3frddi8y50ou.sim.RelationshipState(holder = h, other = "ghost", friendship = 40f)
        save.social.relationships["$h>$h"] = app.rork.p7buzvrzcw3frddi8y50ou.sim.RelationshipState(holder = h, other = h, friendship = 99f)
        save.social.cooldowns["$r|$h"] = 500.0
        save.social.cooldowns["stale|$r"] = 500.0

        SaveBinding.bind(save, content)

        assertTrue("the real opinion survives", save.social.relationships.containsKey("$h>$r"))
        assertFalse("the opinion of nobody is let go", save.social.relationships.containsKey("$h>ghost"))
        assertFalse("nobody holds an opinion of themselves", save.social.relationships.containsKey("$h>$h"))
        assertTrue("a met pair's cooldown survives, rewritten", save.social.cooldowns.containsKey("$h|$r") || save.social.cooldowns.containsKey("$r|$h"))
        assertFalse("a stale pair with nobody in it is let go", save.social.cooldowns.containsKey("stale|$r"))
        val reported = app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics.state.value.entries.map { it.message }.joinToString(" ")
        assertTrue("the drop was reported", reported.contains("person") || reported.contains("ghost"))
    }

    // ------------------------------------------------------------ determinism

    @Test
    fun `the same world and the same meetings give the same social history`() {
        fun run(): World {
            val w = socialWorld()
            val hawk = w.hawk(); val wren = w.wren()
            for (day in 1..8) {
                w.save.minutes += 100.0 * day
                w.social.encounter(hawk, wren)
                w.social.sharedWork(hawk, wren)
                w.social.playerTrade(wren)
            }
            w.social.playerRescue(wren, "bear")
            return w
        }
        val a = run(); val b = run()

        assertEquals(a.save.social, b.save.social)
        assertEquals(
            a.save.npcs.map { it.experiences.toList() },
            b.save.npcs.map { it.experiences.toList() },
        )
    }
}
