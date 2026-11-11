package app.rork.p7buzvrzcw3frddi8y50ou.sim.systems

import app.rork.p7buzvrzcw3frddi8y50ou.data.GoalSeedDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.GoalState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.LifeStages
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Npc
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcActions
import app.rork.p7buzvrzcw3frddi8y50ou.sim.SocialEvents
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World

/**
 * The wishes a person carries for years: to master a craft, to save up, to grow surer among
 * their neighbours, to be closer to their kin, to do their part for the household, to stand on
 * their own feet.
 *
 * Wishes are seeded from content where a pack writes them, and grown from character where it
 * does not — at most one apiece, deterministically. They are pursued only in a person's free
 * hours, behind danger, critical needs, the day's schedule and the household's duties, and
 * their progress is abstract: it accrues from the actions a life already takes, never from a
 * separate timer.
 */
class GoalSystem(private val w: World) {
    private val save get() = w.save

    // Counters for the diagnostics report.
    var goalsSeeded = 0
    var goalsCompleted = 0

    // ------------------------------------------------------------ binding

    /**
     * Seeds each life's wishes: the pack's first, then one grown from character for anyone
     * with none. Existing wishes are sanitised and kept — a wish survives saving and loading.
     */
    fun bind() {
        for (n in w.npcs) {
            val s = n.s
            s.goals.forEach { it.sanitize() }
            s.goals.removeAll { it.id.isBlank() || it.type !in GoalState.TYPES }
            for (seed in n.def.goals) {
                if (seed.type !in GoalState.TYPES) {
                    app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics.record(
                        "content",
                        "The person '${n.def.id}' carries a wish of the unknown kind '${seed.type}'. It was let go.",
                    )
                    continue
                }
                if (seedGoal(s, seed.type, seed.target, seed.priority, authored = true)) goalsSeeded++
            }
            if (s.goals.none { it.status == GoalState.ACTIVE }) {
                grownFrom(n)?.let { if (seedGoal(s, it.type, it.target, it.priority, authored = false)) goalsSeeded++ }
            }
        }
    }

    private fun seedGoal(s: app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcState, type: String, target: String, priority: Int, authored: Boolean): Boolean {
        val id = "g:$type${if (target.isEmpty()) "" else ":$target"}"
        if (s.goals.any { it.id == id }) return false
        s.goals += GoalState(
            id = id, type = type, target = target,
            priority = if (authored) priority else GENERATED_PRIORITY,
            minute = save.minutes,
        )
        return true
    }

    /** The wish [n]'s character grows, when their content wrote none. Deterministic. */
    private fun grownFrom(n: Npc): GoalSeedDef? {
        val stage = LifeStages.stageOf(n.def.age)
        if (LifeStages.isDependant(stage) && stage != LifeStages.ADOLESCENT) return null
        return when {
            n.trait("ambition") >= 0.65f -> GoalSeedDef("improve_skill", target = n.def.profession)
            n.trait("sociability") >= 0.65f -> GoalSeedDef("social_confidence")
            n.trait("loyalty") >= 0.65f && householdSize(n) >= 2 -> GoalSeedDef("support_household")
            else -> GoalSeedDef("save_gold")
        }
    }

    private fun householdSize(n: Npc): Int = w.households.of(n.def.id)?.members?.size ?: 0

    // ------------------------------------------------------------ reading

    /** The wish [n] is pursuing: their most pressing active one, if any. */
    fun suggest(n: Npc): GoalState? = n.s.goals
        .filter { it.status == GoalState.ACTIVE }
        .minByOrNull { it.priority }

    /** The journal's words for a wish. */
    fun text(n: Npc, g: GoalState): String = when (g.type) {
        "improve_skill" -> "To master the ${g.target.ifEmpty { "craft" }} trade"
        "save_gold" -> "To save up a tidy sum"
        "social_confidence" -> "To grow surer among their neighbours"
        "closer_to_relative" -> "To be closer to ${w.npcById[g.target]?.def?.name ?: "their kin"}"
        "support_household" -> "To do their part for the household"
        "independence" -> "To stand on their own feet"
        else -> g.id
    }

    // ------------------------------------------------------------ progress

    /**
     * A life's hours accrue progress on its wishes. Called from the same paths that pay wages
     * and fill needs, so near, mid-ring and distant lives all advance alike. A higher-priority
     * wish is pursued more intently.
     */
    fun progress(n: Npc, action: String, hours: Float) {
        if (hours <= 0f) return
        val goals = n.s.goals.filter { it.status == GoalState.ACTIVE }
        if (goals.isEmpty()) return
        for (g in goals) {
            val base = rateFor(g.type, action) ?: continue
            val intent = 0.75f + g.priority.coerceAtMost(100) / 200f
            g.progress = (g.progress + base * intent * hours).coerceIn(0f, 100f)
            if (g.progress >= 100f) complete(n, g)
        }
    }

    /** How fast [type] advances while [action] is the day's work, or null when it does not. */
    private fun rateFor(type: String, action: String): Float? = when (type) {
        "improve_skill" -> when (action) {
            NpcActions.WORK -> 2.2f
            else -> null
        }
        "save_gold" -> when (action) {
            NpcActions.WORK -> 1.8f
            NpcActions.SHOP -> -0.8f
            else -> null
        }
        "social_confidence" -> when (action) {
            NpcActions.SOCIAL -> 2.5f
            NpcActions.VISIT -> 2.0f
            else -> null
        }
        "closer_to_relative" -> when (action) {
            NpcActions.VISIT -> 3.0f
            NpcActions.SOCIAL -> 1.2f
            else -> null
        }
        "support_household" -> when (action) {
            NpcActions.COOK, NpcActions.HOUSEWORK, NpcActions.CARE -> 2.8f
            else -> null
        }
        "independence" -> when (action) {
            NpcActions.WORK -> 1.2f
            NpcActions.WANDER -> 0.5f
            else -> null
        }
        else -> null
    }

    /** A wish achieved: the life is noted in the chronicle, and the household remembers. */
    private fun complete(n: Npc, g: GoalState) {
        g.status = GoalState.DONE
        g.doneAt = save.minutes
        goalsCompleted++
        val text = completionText(n, g)
        w.record(text)
        w.journalDirty = true
        val hh = w.households.of(n.def.id)
        w.social.event(
            SocialEvents.GOAL_COMPLETED, involved = listOf(n.def.id), sentiment = 1,
            significance = 55f, witnessedBy = hh?.members?.toList() ?: listOf(n.def.id),
            place = hh?.let { w.content.buildings[it.home]?.name }.orEmpty(), text = text,
        )
    }

    private fun completionText(n: Npc, g: GoalState): String = when (g.type) {
        "improve_skill" -> "${n.def.name} has sharpened their ${g.target.ifEmpty { "craft" }} to their satisfaction."
        "save_gold" -> "${n.def.name} has saved up a tidy sum."
        "social_confidence" -> "${n.def.name} has grown surer among their neighbours."
        "closer_to_relative" -> "${n.def.name} has drawn closer to ${w.npcById[g.target]?.def?.name ?: "their kin"}."
        "support_household" -> "${n.def.name} has done their part for the household, and it shows."
        "independence" -> "${n.def.name} stands more on their own feet these days."
        else -> "${n.def.name} has achieved something long wished for."
    }

    companion object {
        /** Wishes grown from character press less than those a pack wrote on purpose. */
        const val GENERATED_PRIORITY = 60
    }
}
