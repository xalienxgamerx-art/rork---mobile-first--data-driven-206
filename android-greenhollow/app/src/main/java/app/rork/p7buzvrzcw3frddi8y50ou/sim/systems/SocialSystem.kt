package app.rork.p7buzvrzcw3frddi8y50ou.sim.systems

import app.rork.p7buzvrzcw3frddi8y50ou.sim.Calendar
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PLAYER_ID
import app.rork.p7buzvrzcw3frddi8y50ou.sim.MemoryEntry
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Npc
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcActions
import app.rork.p7buzvrzcw3frddi8y50ou.sim.GossipKnowledge
import app.rork.p7buzvrzcw3frddi8y50ou.sim.GossipRecord
import app.rork.p7buzvrzcw3frddi8y50ou.sim.RelationshipState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SocialEvents
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The vale's social world: who thinks what of whom, what they remember, and the stories that
 * travel between them.
 *
 * One system owns all three, so there are no competing friendship records: the old single-number
 * bonds of earlier saves live on as one dimension each, and [DialogueSystem.warm] writes the
 * player's standing here rather than in a parallel score.
 *
 * Everything is deterministic. The only dice are the world's own [WorldRng]; memory fading is a
 * pure function of the world clock rather than a per-tick mutation, so the same save and inputs
 * always give the same social history.
 */
class SocialSystem(private val w: World) {
    private val save get() = w.save
    private val social get() = save.social
    private val rnd get() = w.rng

    // Counters for the diagnostics report.
    var relChanges = 0
    var memoriesCreated = 0
    var memoriesPruned = 0
    var gossipCreated = 0
    var gossipShared = 0
    var gossipRejected = 0
    var socialAttempts = 0
    var socialCompleted = 0
    var socialSkipped = 0
    var invalidRefs = 0

    // ------------------------------------------------------------ relationships

    /** The opinion [holder] holds of [other], created blank the first time it is asked for. */
    fun rel(holder: String, other: String): RelationshipState {
        require(holder != other) { "a person holds no opinion of themselves ($holder)" }
        val key = "$holder>$other"
        return social.relationships.getOrPut(key) {
            val r = RelationshipState(holder = holder, other = other)
            social.relationships[key] = r
            r
        }
    }

    /** One measure of one opinion, by dimension name — for dialogue conditions and views. */
    fun dim(holder: String, other: String, name: String): Float = when (name) {
        "familiarity" -> rel(holder, other).familiarity
        "friendship" -> rel(holder, other).friendship
        "trust" -> rel(holder, other).trust
        "respect" -> rel(holder, other).respect
        "affection" -> rel(holder, other).affection
        "fear" -> rel(holder, other).fear
        "resentment" -> rel(holder, other).resentment
        "hostility" -> rel(holder, other).hostility
        else -> 0f
    }

    /** Moves one measure of one opinion, proportionate and bounded. This is the only writer. */
    fun adjust(holder: String, other: String, name: String, delta: Float) {
        if (abs(delta) < 0.01f || holder == other) return
        val r = rel(holder, other)
        val before = when (name) {
            "familiarity" -> r.familiarity
            "friendship" -> r.friendship
            "trust" -> r.trust
            "respect" -> r.respect
            "affection" -> r.affection
            "fear" -> r.fear
            "resentment" -> r.resentment
            "hostility" -> r.hostility
            else -> return
        }
        val after = (before + delta).coerceIn(0f, 100f)
        if (after == before) return
        when (name) {
            "familiarity" -> r.familiarity = after
            "friendship" -> r.friendship = after
            "trust" -> r.trust = after
            "respect" -> r.respect = after
            "affection" -> r.affection = after
            "fear" -> r.fear = after
            "resentment" -> r.resentment = after
            "hostility" -> r.hostility = after
        }
        relChanges++
        milestones(r)
    }

    /**
     * The chronicle notes the turning points of village life as they happen, so the player can
     * discover them in the journal rather than through any UI meter.
     */
    private fun milestones(r: RelationshipState) {
        val who = nameOf(r.holder) ?: return
        val about = nameOf(r.other) ?: return
        val pair = "$who|${about}|${r.friendship}|${r.hostility}"
        if (pair == lastMilestone) return
        lastMilestone = pair
        if (r.friendship >= 60f && r.hostility < 20f) {
            w.record("$who and $about have become fast friends.")
        } else if (r.hostility >= 50f) {
            w.record("$who has fallen out badly with $about.")
        } else if (r.resentment >= 60f && r.friendship < 25f) {
            w.record("$who nurses a quiet grudge against $about.")
        }
    }

    private var lastMilestone = ""

    /** One-word reading of an opinion, for views and dialogue. The player's copy shows this too. */
    fun standing(holder: String, other: String): String {
        val r = rel(holder, other)
        return when {
            r.hostility >= 55f -> "hostile"
            r.resentment >= 50f || r.hostility >= 30f -> "wary"
            r.fear >= 55f -> "afraid"
            r.warmth() >= 45f -> "warm"
            r.familiarity >= 40f -> "familiar"
            r.familiarity >= 12f -> "acquaintance"
            else -> "stranger"
        }
    }

    // ------------------------------------------------------------ memories

    /**
     * Records that something happened. Everyone listed in [witnessedBy] keeps their own copy;
     * the event's rules then move the opinions each keeper holds of the others. Returns the
     * keeper's own memory, or null when they have seen it all before.
     */
    fun event(
        type: String,
        involved: List<String>,
        sentiment: Int,
        significance: Float,
        witnessedBy: List<String> = involved,
        place: String = "",
        text: String = "",
    ): MemoryEntry? {
        var made: MemoryEntry? = null
        for (holder in witnessedBy) {
            if (holder == PLAYER_ID) continue // the player's own history lives in the journal
            val others = involved.filter { it != holder }.distinct()
            val memory = addMemory(holder, type, others, sentiment, significance, witnessed = true, place = place, text = text)
                ?: continue
            made = memory
            for (other in others) applyRules(holder, other, type, significance, sentiment)
        }
        return made
    }

    /** Adds one memory to one keeper, honouring duplicates and the retention cap. */
    private fun addMemory(
        holder: String,
        type: String,
        others: List<String>,
        sentiment: Int,
        significance: Float,
        witnessed: Boolean,
        confidence: Float = 1f,
        place: String = "",
        text: String = "",
    ): MemoryEntry? {
        val keeper = w.npcById[holder] ?: return null
        // The same event retold within a day does not impress twice.
        val fresh = keeper.s.experiences.none {
            it.type == type && it.others.toSet() == others.toSet() && save.minutes - it.minute < DUPLICATE_WINDOW
        }
        if (!fresh) return null
        val entry = MemoryEntry(
            id = "m:${holder}:${save.minutes.toInt()}:${rnd.nextInt(1_000_000)}",
            type = type, minute = save.minutes,
            significance = significance.coerceIn(0f, 100f),
            sentiment = sentiment, confidence = confidence.coerceIn(0f, 1f),
            witnessed = witnessed, others = others, place = place, text = text,
        )
        keeper.s.experiences += entry
        memoriesCreated++
        w.journalDirty = true
        prune(holder, keeper)
        return entry
    }

    /** Keeps each life's memory store finite: recent and weighty memories crowd out small ones. */
    private fun prune(holder: String, keeper: Npc) {
        val now = save.minutes
        while (keeper.s.experiences.size > MAX_MEMORIES) {
            val weakest = keeper.s.experiences
                .withIndex()
                .filter { it.value.significance < MemoryEntry.PROTECTED_SIGNIFICANCE }
                .minByOrNull { effective(it.value, now) } ?: return
            keeper.s.experiences.removeAt(weakest.index)
            memoriesPruned++
        }
    }

    /**
     * How strongly a memory is felt [now]: strong events keep their edge for weeks, small ones
     * fade within days. A significant event never fades below its historical weight — the
     * feeling dulls, but the fact stays in the life.
     */
    fun effective(m: MemoryEntry, now: Double): Float {
        val days = max(0.0, (now - m.minute) / 1440.0)
        val faded = m.significance * 0.5f.pow((days / HALF_LIFE_DAYS).toFloat())
        return if (m.significance >= MemoryEntry.PROTECTED_SIGNIFICANCE) {
            max(faded, MemoryEntry.HISTORICAL_SIGNIFICANCE)
        } else {
            faded
        }
    }

    /** How one kind of event moves one opinion. Scaled by weight and personality, never random. */
    private fun applyRules(holder: String, other: String, type: String, significance: Float, sentiment: Int) {
        val weight = (significance / 50f).coerceIn(0.2f, 2f)
        val k = w.npcById[holder]
        // Personality bends how strongly a person is moved, always the same way for the same person.
        val forgiving = 1.3f - (k?.trait("generosity") ?: 0.5f) * 0.6f   // the generous let go sooner
        val warm = 0.7f + (k?.trait("sociability") ?: 0.5f) * 0.6f       // the sociable warm faster
        val trusting = 0.6f + (k?.trait("loyalty") ?: 0.5f) * 0.8f       // the loyal learn to trust
        val timid = 1.4f - (k?.trait("courage") ?: 0.5f) * 0.8f          // the timid frighten easier

        fun plus(name: String, amount: Float, factor: Float = 1f) =
            adjust(holder, other, name, amount * weight * factor)

        when (type) {
            SocialEvents.CONVERSATION -> {
                plus("familiarity", 6f)
                plus("friendship", 1.5f, warm)
            }
            SocialEvents.SHARED_WORK -> {
                plus("familiarity", 7f)
                plus("trust", 2f, trusting)
                plus("respect", 1.5f)
            }
            SocialEvents.GOSSIP -> plus("familiarity", 3f, warm)
            SocialEvents.ARGUMENT -> {
                plus("resentment", 8f * forgiving)
                plus("hostility", 4f * forgiving)
                plus("friendship", -4f)
            }
            SocialEvents.INSULT -> {
                plus("resentment", 10f * forgiving)
                plus("hostility", 6f * forgiving)
                plus("friendship", -6f)
            }
            SocialEvents.RECONCILIATION -> {
                plus("resentment", -12f)
                plus("hostility", -10f)
                plus("friendship", 4f, warm)
            }
            SocialEvents.FRIENDSHIP_FORMED -> plus("friendship", 8f, warm)
            SocialEvents.HELP_RECEIVED -> {
                plus("trust", 10f, trusting)
                plus("affection", 6f, warm)
                plus("friendship", 5f, warm)
            }
            SocialEvents.HELP_REFUSED -> plus("trust", -8f, trusting)
            SocialEvents.BETRAYAL -> {
                plus("trust", -20f * trusting)
                plus("friendship", -15f)
                plus("resentment", 18f * forgiving)
                plus("hostility", 10f * forgiving)
            }
            SocialEvents.THEFT -> {
                plus("trust", -15f * trusting)
                plus("hostility", 12f * forgiving)
                plus("resentment", 12f * forgiving)
                plus("fear", 5f * timid)
            }
            SocialEvents.COMBAT -> plus("fear", 10f * timid)
            SocialEvents.RESCUE -> {
                plus("trust", 12f, trusting)
                plus("affection", 10f, warm)
                plus("respect", 8f)
                plus("friendship", 6f, warm)
            }
            SocialEvents.TRADE_DEAL -> {
                plus("familiarity", 4f)
                plus("trust", 2f, trusting)
            }
            SocialEvents.TRADE_DISPUTE -> {
                plus("trust", -8f, trusting)
                plus("resentment", 8f * forgiving)
            }
            SocialEvents.AFFECTION -> plus("affection", 8f, warm)
            SocialEvents.IMPORTANT_CONVERSATION -> {
                plus("familiarity", 8f)
                plus("friendship", 5f, warm)
                plus("trust", 3f, trusting)
            }
            SocialEvents.PUBLIC_HUMILIATION -> {
                plus("resentment", 15f * forgiving)
                plus("hostility", 10f * forgiving)
                plus("affection", -8f)
            }
            SocialEvents.CRIME_WITNESSED -> {
                plus("fear", 10f * timid)
                plus("trust", -10f, trusting)
                plus("hostility", 8f * forgiving)
            }
            SocialEvents.PROMISE_BROKEN -> {
                plus("trust", -15f * trusting)
                plus("resentment", 10f * forgiving)
            }
            SocialEvents.BRAVERY_WITNESSED -> {
                plus("respect", 12f)
                plus("friendship", 3f, warm)
            }
            SocialEvents.LOCAL_EVENT -> plus("familiarity", 3f)
            else -> {
                // An unknown kind from a pack: moved by its sentiment alone, never dropped.
                if (sentiment >= 0) plus("familiarity", 4f) else plus("resentment", 6f * forgiving)
            }
        }
    }

    // ------------------------------------------------------------ gossip

    /**
     * A deed worth telling becomes a story the doer's witnesses carry. The event stays
     * identifiable under the story however far the telling bends it.
     */
    fun startStory(eventRef: String, category: String, subject: String, source: String, text: String, importance: Float) {
        if (social.gossip.any { it.eventRef == eventRef }) return
        val record = GossipRecord(
            id = "gp:$eventRef", eventRef = eventRef, category = category, subject = subject,
            source = source, originMinute = save.minutes, text = text,
            importance = importance.coerceIn(0f, 100f),
        )
        record.holders[source] = GossipKnowledge(minute = save.minutes)
        social.gossip += record
        gossipCreated++
        while (social.gossip.size > MAX_STORIES) {
            // The oldest, dullest story slips out of circulation first; the player's are kept.
            val drop = social.gossip
                .withIndex()
                .filter { PLAYER_ID !in it.value.holders }
                .minByOrNull { it.value.importance + recencyBonus(it.value) } ?: return
            social.gossip.removeAt(drop.index)
        }
    }

    private fun recencyBonus(g: GossipRecord): Float = ((save.minutes - g.originMinute) / 1440.0).toFloat()

    /** True when [holder] carries a story of [category] (or the exact story [category] names). */
    fun knows(holder: String, category: String): Boolean = social.gossip.any { g ->
        (g.category == category || g.id == category || g.eventRef == category) && holder in g.holders
    }

    /**
     * The story as [holder] knows it: the further it has travelled, the more the telling bends.
     * A pure function of the record and the holder's knowledge, so it never wobbles between reads.
     */
    fun storyText(record: GossipRecord, holder: String): String {
        val k = record.holders[holder] ?: return record.text
        return when {
            k.distortion < 0.34f -> record.text
            k.distortion < 0.67f -> "${record.text} — or so it is said, though the details shift with each telling."
            else -> "They say ${subjectName(record.subject)} ${exaggerate(record)} — a tale that grows longer every day."
        }
    }

    private fun exaggerate(record: GossipRecord): String = when (record.category) {
        SocialEvents.RESCUE -> "faced down the beast itself"
        SocialEvents.BETRAYAL, SocialEvents.THEFT, SocialEvents.CRIME_WITNESSED -> "did worse than anyone will say aloud"
        SocialEvents.ARGUMENT, SocialEvents.INSULT -> "made the whole square stop and stare"
        else -> "was at the centre of it, somehow"
    }

    private fun subjectName(id: String): String = nameOf(id) ?: "someone"

    /**
     * One conversation, one story told. Whether it passes depends on the speaker's nature, their
     * trust in the listener, and how much the story matters; the listener's copy is a little less
     * sure and a little more bent than the speaker's own.
     */
    fun shareGossip(speaker: Npc, listener: Npc): Boolean {
        // A listener hears a story once, so the encounter cooldown is what bounds the telling;
        // what matters is whether the speaker carries something the listener has not heard.
        val talkable = social.gossip.filter { g ->
            speaker.def.id in g.holders && listener.def.id !in g.holders
        }
        val story = talkable.maxByOrNull { it.importance } ?: return false
        val trust = rel(speaker.def.id, listener.def.id).trust
        val chance = 0.30f + speaker.trait("sociability") * 0.35f + trust / 250f +
            story.importance / 400f - (if (story.importance < 25f) 0.25f else 0f)
        if (rnd.nextFloat() >= chance) {
            gossipRejected++
            return false
        }
        val told = story.holders[speaker.def.id]!!
        // Each retelling is a little less sure and a little more bent; chatty gossips bend more.
        val bend = 0.06f + (1f - listener.trait("loyalty")) * 0.10f + listener.trait("curiosity") * 0.06f
        story.holders[listener.def.id] = GossipKnowledge(
            minute = save.minutes,
            confidence = (told.confidence * 0.86f).coerceIn(0.1f, 1f),
            distortion = (told.distortion + bend).coerceIn(0f, 1f),
            via = speaker.def.id,
        )
        gossipShared++
        // Hearing a reliable account moves the listener's opinion of who the story is about —
        // less than seeing it would, and less again if the teller is doubted.
        val weight = story.holders[listener.def.id]!!.confidence * 40f
        val grim = story.category in setOf(
            SocialEvents.BETRAYAL, SocialEvents.THEFT, SocialEvents.INSULT, SocialEvents.CRIME_WITNESSED,
            SocialEvents.ARGUMENT, SocialEvents.TRADE_DISPUTE, SocialEvents.PUBLIC_HUMILIATION, SocialEvents.PROMISE_BROKEN,
        )
        if (story.subject != listener.def.id) {
            if (grim) {
                adjust(listener.def.id, story.subject, "resentment", 6f * weight / 40f)
                adjust(listener.def.id, story.subject, "trust", -4f * weight / 40f)
            } else {
                adjust(listener.def.id, story.subject, "respect", 4f * weight / 40f)
                adjust(listener.def.id, story.subject, "affection", 2f * weight / 40f)
            }
        }
        addMemory(
            listener.def.id, SocialEvents.GOSSIP, listOf(speaker.def.id), sentiment = if (grim) -1 else 1,
            significance = (story.importance * 0.4f).coerceIn(5f, 45f), witnessed = false,
            confidence = story.holders[listener.def.id]!!.confidence,
            text = storyText(story, listener.def.id),
        )
        return true
    }

    /** What the player has heard, for the journal: the stories they hold, surest first. */
    fun playerStories(): List<Pair<GossipRecord, GossipKnowledge>> =
        social.gossip.mapNotNull { g -> g.holders[PLAYER_ID]?.let { g to it } }
            .sortedByDescending { (g, _) -> g.importance }

    /**
     * The player hears a story from a villager: it goes into the journal through the same
     * rumours page as everything else they learn, never into a separate one.
     */
    fun hearStory(record: GossipRecord, from: Npc) {
        if (PLAYER_ID in record.holders) return
        val told = record.holders[from.def.id] ?: return
        record.holders[PLAYER_ID] = GossipKnowledge(
            minute = save.minutes,
            confidence = (told.confidence * 0.9f).coerceIn(0.1f, 1f),
            distortion = told.distortion,
            via = from.def.id,
        )
        w.record("${pName()} heard the story of ${subjectName(record.subject)} from ${from.def.name}.")
        w.journalDirty = true
    }

    private fun pName() = save.player.name

    // ------------------------------------------------------------ encounters

    /** True when the pair may interact again; records the meeting when they may. */
    private fun ready(a: Npc, b: Npc): Boolean {
        val key = pairKey(a.def.id, b.def.id)
        val last = social.cooldowns[key] ?: return true
        return save.minutes - last >= COOLDOWN_MINUTES
    }

    private fun markMet(a: Npc, b: Npc) {
        social.cooldowns[pairKey(a.def.id, b.def.id)] = save.minutes
        val cutoff = save.minutes - 2880.0
        social.cooldowns.entries.removeAll { it.value < cutoff }
    }

    private fun pairKey(a: String, b: String): String = if (a < b) "$a|$b" else "$b|$a"

    /**
     * Two townsfolk with time for one another: a chat, an argument, a story passed on. This is
     * the social decision layer the schedules call into; it never overrides danger or critical
     * needs, because the caller only reaches here when life is calm.
     */
    fun encounter(a: Npc, b: Npc) {
        socialAttempts++
        if (!ready(a, b)) {
            socialSkipped++
            return
        }
        markMet(a, b)
        socialCompleted++
        val r = rel(a.def.id, b.def.id)
        val temper = (a.trait("aggression") + b.trait("aggression")) / 2f
        // Old grudges and hot tempers spark; warmth and respect settle the day.
        val quarrelChance = temper * 0.30f + r.resentment / 300f - r.friendship / 400f
        if (rnd.nextFloat() < quarrelChance) {
            event(SocialEvents.ARGUMENT, listOf(a.def.id, b.def.id), -1, 30f, place = a.destName)
            if (r.resentment >= 60f) w.record("${a.def.name} and ${b.def.name} argued bitterly again.")
        } else {
            event(SocialEvents.CONVERSATION, listOf(a.def.id, b.def.id), 1, 12f, place = a.destName)
            if (r.familiarity in 30f..37f) w.record("${a.def.name} and ${b.def.name} are getting to know one another.")
            shareGossip(a, b)
            if (r.friendship >= 35f && rnd.nextFloat() < 0.2f) shareGossip(b, a)
        }
    }

    /** Working side by side: familiarity and quiet trust, mostly unremarked. */
    fun sharedWork(a: Npc, b: Npc) {
        socialAttempts++
        if (!ready(a, b)) {
            socialSkipped++
            return
        }
        markMet(a, b)
        socialCompleted++
        adjust(a.def.id, b.def.id, "familiarity", 2.5f)
        adjust(b.def.id, a.def.id, "familiarity", 2.5f)
        adjust(a.def.id, b.def.id, "trust", 0.6f)
        adjust(b.def.id, a.def.id, "trust", 0.6f)
        // The occasional shared shift is remembered; the daily grind is not.
        if (rnd.nextFloat() < 0.12f) {
            event(SocialEvents.SHARED_WORK, listOf(a.def.id, b.def.id), 1, 14f, place = a.workName)
        }
    }

    // The player's part in it all ---------------------------------------------

    /** The player spoke with someone: familiarity grows, and they may hear the local news. */
    fun playerTalk(n: Npc, first: Boolean) {
        if (first) {
            event(SocialEvents.CONVERSATION, listOf(n.def.id, PLAYER_ID), 1, 14f)
        }
        // The player is told a story in conversation too, through the same rules as villagers.
        if (knows(n.def.id, SocialEvents.GOSSIP) || social.gossip.any { n.def.id in it.holders }) {
            if (rnd.nextFloat() < 0.35f + n.trait("sociability") * 0.3f) {
                social.gossip.firstOrNull { n.def.id in it.holders && PLAYER_ID !in it.holders }?.let { hearStory(it, n) }
            }
        }
    }

    /** The player gave a gift: remembered fondly. */
    fun playerGift(n: Npc) {
        event(SocialEvents.HELP_RECEIVED, listOf(n.def.id, PLAYER_ID), 1, 35f)
        adjust(n.def.id, PLAYER_ID, "affection", 5f)
    }

    /** The player traded fairly: a merchant's trust, one deal at a time. */
    fun playerTrade(n: Npc) {
        socialAttempts++
        socialCompleted++
        adjust(n.def.id, PLAYER_ID, "familiarity", 2f)
        adjust(n.def.id, PLAYER_ID, "trust", 1.2f)
        if (rnd.nextFloat() < 0.25f) {
            event(SocialEvents.TRADE_DEAL, listOf(n.def.id, PLAYER_ID), 1, 12f)
        }
    }

    /** The player brought down a beast that had someone fleeing for home: remembered a long time. */
    fun playerRescue(n: Npc, beast: String) {
        event(SocialEvents.RESCUE, listOf(n.def.id, PLAYER_ID), 1, 75f, text = "Saved by ${pName()} from a $beast")
        w.toast("${n.def.name} will remember this.")
    }

    // The far-off lives -------------------------------------------------------

    /**
     * The abstract pass for lives the player cannot see: relationship drift with whoever shares
     * the day's workplace, and the occasional story passed at a distance. No scenes are staged;
     * a few bounded rolls answer for hours.
     */
    fun abstractLife(n: Npc, action: String, hours: Float) {
        if (hours <= 0f) return
        when (action) {
            NpcActions.WORK -> {
                if (rnd.nextFloat() > hours * 0.08f) return
                val mate = coworkerOf(n) ?: return
                adjust(n.def.id, mate, "familiarity", 2f)
                adjust(n.def.id, mate, "trust", 0.5f)
            }
            NpcActions.SOCIAL -> {
                if (rnd.nextFloat() > hours * 0.15f) return
                val mate = acquaintanceOf(n) ?: return
                val other = w.npcById[mate] ?: return
                encounter(n, other)
            }
        }
    }

    private fun coworkerOf(n: Npc): String? = w.npcs
        .firstOrNull { it !== n && it.workName == n.workName && it.workName.isNotEmpty() }?.def?.id

    private fun acquaintanceOf(n: Npc): String? = w.npcs
        .filter { it !== n && rel(n.def.id, it.def.id).familiarity > 5f }
        .maxByOrNull { rel(n.def.id, it.def.id).friendship }?.def?.id

    private fun nameOf(id: String): String? = if (id == PLAYER_ID) pName() else w.npcById[id]?.def?.name

    companion object {
        /** Detailed memories kept per person; weightier ones survive the cut. */
        const val MAX_MEMORIES = 20
        /** Stories in circulation at once. */
        const val MAX_STORIES = 40
        /** Days for a memory's edge to halve. */
        const val HALF_LIFE_DAYS = 6f
        /** Same event, same people, inside this window: remembered once. */
        const val DUPLICATE_WINDOW = 1440.0
        /** Game minutes before the same pair interact socially again. */
        const val COOLDOWN_MINUTES = 90.0
    }
}
