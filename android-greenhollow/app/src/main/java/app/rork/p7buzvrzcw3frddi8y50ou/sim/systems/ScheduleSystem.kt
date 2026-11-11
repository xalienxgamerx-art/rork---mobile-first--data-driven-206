package app.rork.p7buzvrzcw3frddi8y50ou.sim.systems

import app.rork.p7buzvrzcw3frddi8y50ou.data.ScheduleDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ScheduleEntryDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Calendar
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Npc
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World

/** A place a schedule window can lead to: a position, the village gate on the way, and its name. */
class Dest(val x: Float, val z: Float, val gate: String = "", val name: String = "")

/**
 * Turns schedule templates into what one villager should be doing at a given minute.
 *
 * A window is a pure function of who follows the template and what the world clock says, so the
 * same person and minute always resolve to the same window: jumping forward hours answers every
 * transition in one question, and nothing depends on how often the question is asked.
 */
class ScheduleSystem(private val w: World) {
    private val content get() = w.content

    /** The template this person follows: their own choice, their trade's, or none at all. */
    fun templateOf(n: Npc): ScheduleDef? = when {
        n.def.schedule.isNotEmpty() -> content.schedules[n.def.schedule]
        n.prof.schedule.isNotEmpty() -> content.schedules[n.prof.schedule]
        else -> null
    }

    /** The window whose hours hold now, conditions and all; the highest priority wins, ties keep order. */
    fun entryFor(n: Npc, minutes: Double): ScheduleEntryDef? {
        val template = templateOf(n) ?: return null
        val h = Calendar.hour(minutes)
        var best: ScheduleEntryDef? = null
        for (e in template.entries) {
            // A window whose start is past its end runs through midnight.
            val inHours = if (e.start < e.end) h >= e.start && h < e.end else h >= e.start || h < e.end
            if (!inHours) continue
            if (e.conditions.isNotEmpty() && !e.conditions.all { w.dialogue.holds(it, n) }) continue
            if (best == null || e.priority > best!!.priority) best = e
        }
        return best
    }

    /**
     * Where a window's target leads: "home", "work", or a spot or building from the content.
     * Resolutions are cached on the person, so the same window is only resolved once, and a
     * target that matches nothing stays null without repeated lookups.
     */
    fun destinationOf(n: Npc, target: String): Dest? {
        if (target.isEmpty()) return null
        if (n.destCache.containsKey(target)) return n.destCache[target]
        val dest: Dest? = when {
            target == "home" -> Dest(n.homeDoor[0], n.homeDoor[1], "", n.homeName)
            target == "work" -> workDest(n)
            else -> {
                val spot = content.spots[target]
                when {
                    spot != null -> Dest(
                        spot.x + n.spread * 1.5f,
                        spot.z + n.spreadAlt * 8f,
                        spot.gate,
                        spot.name,
                    )
                    else -> {
                        val b = content.buildings[target] ?: content.buildingFor(target) ?: w.shop.takeIf { target == "shop" }
                        if (b == null) null else {
                            val d = w.doorOf(b, n.spread)
                            Dest(d[0], d[1], "", b.name)
                        }
                    }
                }
            }
        }
        n.destCache[target] = dest
        return dest
    }

    /** The trade's workplace, or home when there is none. */
    fun workDest(n: Npc): Dest? {
        val pos = n.workPos ?: return Dest(n.homeDoor[0], n.homeDoor[1], "", n.homeName)
        return Dest(pos[0], pos[1], n.workGate, n.workName)
    }
}
