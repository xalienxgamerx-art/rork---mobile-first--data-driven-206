package app.rork.p7buzvrzcw3frddi8y50ou.sim.systems

import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import app.rork.p7buzvrzcw3frddi8y50ou.data.HouseholdDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.HouseholdEvent
import app.rork.p7buzvrzcw3frddi8y50ou.sim.HouseholdSave
import app.rork.p7buzvrzcw3frddi8y50ou.sim.LifeStages
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Npc
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcActions
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SocialEvents
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World

/**
 * The vale's households: who lives under which roof, and how their shared life goes.
 *
 * A household is not a family. Membership here says only who shares a residence and its
 * circumstances; kinship is [KinshipSystem]'s business, and neither implies the other. One
 * person belongs to at most one household, and membership is the single source of truth —
 * nothing is copied onto the person that can fall out of step.
 *
 * There is no household clock. Food and the state of the home move only through the members'
 * own actions (cooking fills the larder, eating drains it, housework mends the place), so the
 * cost of a household is proportional to its members and the simulation stays deterministic.
 */
class HouseholdSystem(private val w: World) {
    private val save get() = w.save

    // Counters for the diagnostics report.
    var membershipChanges = 0
    var dutiesAssigned = 0

    /** member id -> their household, rebuilt only when membership changes. */
    private var index: Map<String, HouseholdSave>? = null

    fun byId(id: String): HouseholdSave? = save.households.firstOrNull { it.id == id }

    /** The household [npcId] lives in, or null when they live under no roof recorded. */
    fun of(npcId: String): HouseholdSave? {
        val idx = index ?: save.households
            .flatMap { hh -> hh.members.map { it to hh } }
            .toMap()
            .also { index = it }
        return idx[npcId]
    }

    private fun invalidate() {
        index = null
    }

    /** The household's own record of what happened, trimmed from the front. */
    fun note(hh: HouseholdSave, text: String) {
        hh.history += HouseholdEvent(save.minutes, text)
        while (hh.history.size > HouseholdSave.MAX_HISTORY) hh.history.removeAt(0)
        w.journalDirty = true
    }

    // ------------------------------------------------------------ binding

    /**
     * Brings the save's households in line with the content: pack-defined households are
     * instantiated into the save (the save stays authoritative afterwards), every stored id is
     * canonicalised with a report instead of a quiet drop, and anyone left without a roof
     * gains a household of shared residence with whoever shares their home — cohabitants
     * become household, but never family. Deterministic; safe on every load.
     */
    fun bind() {
        // Existing households: canonicalise references, repair the lists, report the broken.
        val known = HashSet<String>()
        for (hh in save.households) {
            val membersBefore = hh.members.size
            hh.members.removeAll { m ->
                val c = w.content.npcs.canonical(m)
                val ok = w.content.npcs[c] != null
                if (!ok) Diagnostics.record("save", "The household '${hh.id}' lists '$m', whom no loaded pack defines. They were let go.")
                !ok
            }
            if (hh.members.removeAll { !known.add(it) }) {
                Diagnostics.record("save", "The household '${hh.id}' listed someone twice. The copy was let go.")
            }
            if (hh.head.isNotEmpty() && hh.head !in hh.members) {
                Diagnostics.record("save", "The household '${hh.id}' named a head who does not live there. No head is recorded.")
                hh.head = ""
            }
            hh.sanitize()
            if (hh.members.isEmpty() && membersBefore > 0) {
                // Kept with its history: an emptied household is a fact of the vale.
                note(hh, "The household of ${hh.name.ifEmpty { hh.id }} stands empty.")
            }
        }

        // Pack definitions not yet in the save are instantiated. A member already claimed by
        // the save's own households is left where they live.
        for (def in w.content.households.values) {
            if (byId(def.id) != null) continue
            val members = LinkedHashSet<String>()
            for (m in def.members) {
                val id = w.content.npcs.canonical(m)
                if (w.content.npcs[id] == null) continue
                if (of(id) != null) continue
                members.add(id)
            }
            if (members.isEmpty()) continue
            val hh = HouseholdSave(
                id = def.id, name = def.name, home = w.content.buildings[def.home]?.id ?: "",
                members = members, head = def.head.takeIf { it in members } ?: "", type = def.type,
            )
            hh.sanitize()
            save.households += hh
            invalidate()
        }

        // One roof to a person: anyone claimed by two households stays with the first roof
        // that holds them, and the later claim is let go. The order of the save decides.
        val claimed = HashSet<String>()
        for (hh in save.households) {
            if (hh.members.removeAll { !claimed.add(it) }) {
                Diagnostics.record("save", "The household '${hh.id}' claimed someone who already lived elsewhere. The second claim was let go.")
            }
        }
        invalidate()

        // Coverage: everyone not yet in a household joins the people who share their home.
        for (n in w.npcs) {
            if (of(n.def.id) != null) continue
            val home = n.def.home
            val existing = save.households.firstOrNull { it.home == home && home.isNotEmpty() }
            if (existing != null) {
                add(existing, n.def.id, quiet = true)
            } else {
                val id = "hh:$home"
                val made = if (byId(id) == null) {
                    val hh = HouseholdSave(
                        id = id,
                        name = homeOf(home)?.name?.let { "The folk of $it" } ?: "A shared roof",
                        home = home,
                        members = linkedSetOf(n.def.id),
                        type = "shared",
                    )
                    save.households += hh
                    hh
                } else {
                    byId(id)!!
                }
                made.members.add(n.def.id)
                invalidate()
            }
        }

        for (hh in save.households) {
            ensureHead(hh)
            assignDuties(hh)
        }
        invalidate()
    }

    private fun homeOf(id: String) = w.content.buildings[id]

    /** A household of two or more recognises an organiser: the most ambitious adult, deterministically. */
    private fun ensureHead(hh: HouseholdSave) {
        if (hh.head.isNotEmpty() || hh.members.size < 2) return
        val head = hh.members
            .filter { !LifeStages.isDependant(LifeStages.stageOf(w.npcById[it]?.def?.age ?: 30)) }
            .maxByOrNull { (w.npcById[it]?.trait("ambition") ?: 0.5f) + (w.npcById[it]?.trait("loyalty") ?: 0.5f) + it.hashCode() * 1e-6f }
        if (head != null) hh.head = head
    }

    // ------------------------------------------------------------ membership

    /** Adds someone to a household. Returns false when they already live elsewhere. */
    fun add(hh: HouseholdSave, npcId: String, quiet: Boolean = false): Boolean {
        if (npcId in hh.members) return true
        if (of(npcId) != null) return false
        hh.members.add(npcId)
        membershipChanges++
        invalidate()
        if (!quiet) {
            note(hh, "${nameOf(npcId)} has joined the household.")
            tellHousehold(hh, listOf(npcId), 1, 20f, "${nameOf(npcId)} has joined the household.")
        }
        assignDuties(hh)
        return true
    }

    /** Removes someone from a household; the household itself is kept, with its history. */
    fun remove(npcId: String, why: String): Boolean {
        val hh = of(npcId) ?: return false
        hh.members.remove(npcId)
        membershipChanges++
        invalidate()
        if (hh.head == npcId) hh.head = ""
        note(hh, "$why ${nameOf(npcId)} left the household.")
        tellHousehold(hh, listOf(npcId), 1, 30f, "$why ${nameOf(npcId)} left the household.")
        assignDuties(hh)
        return true
    }

    /** Moves someone to another household: leaving one roof for another, both households told. */
    fun move(npcId: String, toId: String): Boolean {
        val to = byId(toId) ?: return false
        val from = of(npcId)
        if (from === to) return true
        if (from != null) remove(npcId, "Moving house,")
        return add(to, npcId)
    }

    /** A new household is founded. Returns it, or null when the id is taken. */
    fun establish(id: String, name: String, home: String, members: Set<String>, head: String, type: String): HouseholdSave? {
        if (byId(id) != null) return null
        for (m in members) if (of(m) != null) return null
        val hh = HouseholdSave(
            id = id, name = name, home = home,
            members = LinkedHashSet(members), head = head.takeIf { it in members } ?: "", type = type,
        )
        hh.sanitize()
        save.households += hh
        invalidate()
        membershipChanges++
        if (members.size >= 2) {
            w.record("A new household, $name, has been founded under ${homeOf(home)?.name ?: "an open sky"}.")
        }
        assignDuties(hh)
        return hh
    }

    // ------------------------------------------------------------ duties

    /**
     * Shares the domestic work out by character, deterministically: the generous and loyal
     * step up, the greedy hang back, and children are given only light work. Nobody is scored
     * by chance, so the same people in the same household always share out the same way.
     */
    fun assignDuties(hh: HouseholdSave) {
        val before = hh.duties.toMap()
        val scored = hh.members.map { it to scoreFor(it) }.sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
        val load = HashMap<String, Int>()
        for (d in DUTIES) {
            val eligible = scored.filter { (id, _) -> d !in LIGHT_DUTIES || stageOf(id) == LifeStages.CHILD || stageOf(id) == LifeStages.ADOLESCENT }
                .ifEmpty { scored }
            val chosen = eligible.minByOrNull { (id, _) -> (load[id] ?: 0) * 10f - scoreOf(scored, id) }?.first
            if (chosen != null) {
                hh.duties[d] = chosen
                load[chosen] = (load[chosen] ?: 0) + 1
            }
        }
        dutiesAssigned++
        if (hh.duties.toMap() != before) note(hh, "The work of the household was shared out anew.")
    }

    private fun scoreOf(scored: List<Pair<String, Float>>, id: String) = scored.first { it.first == id }.second

    /** The person's pull towards domestic work, from the character they already have. */
    private fun scoreFor(npcId: String): Float {
        val n = w.npcById[npcId] ?: return 0.5f
        return n.trait("generosity") * 1.2f + n.trait("loyalty") * 1.0f + n.trait("ambition") * 0.6f - n.trait("greed") * 0.9f
    }

    private fun stageOf(npcId: String) = LifeStages.stageOf(w.npcById[npcId]?.def?.age ?: 30)

    /** Who has taken on a duty. */
    fun dutyHolder(hh: HouseholdSave, duty: String): String? = hh.duties[duty]

    /**
     * The domestic work waiting for [n] right now, as an activity word — or null when the
     * household wants for nothing. Called only in a person's free hours, after danger, needs
     * and the day's schedule have had their say.
     */
    fun dutyFor(n: Npc): String? {
        val hh = of(n.def.id) ?: return null
        val h = app.rork.p7buzvrzcw3frddi8y50ou.sim.Calendar.hour(save.minutes)
        if (h < 7f || h >= 21f) return null
        val duty = hh.duties.entries.filter { it.value == n.def.id }.map { it.key }.firstOrNull() ?: return null
        return when (duty) {
            "cooking" -> if (hh.food < 45f) NpcActions.COOK else null
            "cleaning" -> if (hh.condition < 50f) NpcActions.HOUSEWORK else null
            "gathering" -> if (hh.food < 60f) NpcActions.COOK else null
            "repairs" -> if (hh.condition < 40f) NpcActions.HOUSEWORK else null
            else -> null
        }
    }

    // ------------------------------------------------------------ circumstances

    /**
     * The effect of one of the domestic activities on the household, over [hours]. This is the
     * only writer of the household's circumstances besides eating, so cost stays O(members).
     */
    fun applyAction(n: Npc, action: String, hours: Float) {
        if (hours <= 0f) return
        val hh = of(n.def.id) ?: return
        when (action) {
            NpcActions.COOK -> hh.food = (hh.food + 10f * hours).coerceIn(0f, 100f)
            NpcActions.HOUSEWORK -> hh.condition = (hh.condition + 8f * hours).coerceIn(0f, 100f)
            NpcActions.CARE, NpcActions.VISIT -> {}
        }
    }

    /** A meal eaten at home drains the larder. */
    fun eatAtHome(n: Npc, hours: Float) {
        if (hours <= 0f) return
        val hh = of(n.def.id) ?: return
        hh.food = (hh.food - 14f * hours).coerceIn(0f, 100f)
    }

    /** True when the larder is nearly bare, which the journal may tell the player. */
    fun wants(hh: HouseholdSave): String? = when {
        hh.food < 25f -> "The larder is nearly bare."
        hh.condition < 30f -> "The house is falling into disrepair."
        else -> null
    }

    // ------------------------------------------------------------ telling

    /**
     * A household happening is remembered by the members who share the roof, through the same
     * social event path as everything else — weighty ones become stories the vale may carry.
     */
    fun tellHousehold(hh: HouseholdSave, involved: List<String>, sentiment: Int, significance: Float, text: String) {
        w.social.event(
            SocialEvents.HOUSEHOLD_EVENT, involved = involved, sentiment = sentiment,
            significance = significance, witnessedBy = hh.members.toList(), place = homeOf(hh.home)?.name.orEmpty(),
            text = text,
        )
    }

    private fun nameOf(id: String): String = w.npcById[id]?.def?.name ?: id

    companion object {
        /** The duties a household shares out, in the order they are assigned. */
        val DUTIES = listOf("cooking", "cleaning", "gathering", "repairs")
        /** Light work, fit for children. */
        val LIGHT_DUTIES = setOf("gathering", "cleaning")
    }
}
