package app.rork.p7buzvrzcw3frddi8y50ou.sim.systems

import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import app.rork.p7buzvrzcw3frddi8y50ou.data.ScheduleEntryDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.hexToRgb
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Calendar
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Creature
import app.rork.p7buzvrzcw3frddi8y50ou.sim.MIN_PER_SEC
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Npc
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcActions
import app.rork.p7buzvrzcw3frddi8y50ou.sim.NpcState
import app.rork.p7buzvrzcw3frddi8y50ou.sim.StockEntry
import app.rork.p7buzvrzcw3frddi8y50ou.sim.TWO_PI
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The villagers' own lives: needs, schedules, choices, travel, wages and gossip.
 *
 * Everyone is simulated in one of three tiers, chosen by how far they stand from the player:
 * near villagers are walked every frame, the mid ring is stepped twice a second, and far-off
 * lives advance in big abstract jumps that only touch the save's needs and positions. The save
 * records each person's needs, current action and the minute they were last simulated, so a
 * world reloads mid-life and a distant life never needs the frame loop.
 */
class NpcSystem(private val w: World) {
    private val save get() = w.save
    private val content get() = w.content
    private val rnd get() = w.rng

    // Counters for the diagnostics report.
    private var transitions = 0
    private var failedDests = 0
    private var stepMillis = 0f

    fun build() {
        val states = save.npcs.associateBy { it.id }
        for (def in content.npcs.values) {
            val prof = content.professions[def.profession] ?: continue
            val home = content.buildings[def.home] ?: continue
            val hashSide = ((def.id.hashCode() and 0xFF) / 255f - 0.5f) * 4f
            val door = w.doorOf(home, hashSide)
            val state: NpcState = states[def.id] ?: run {
                val created = NpcState(
                    id = def.id, x = door[0], z = door[1],
                    hunger = 55f + rnd.nextFloat() * 35f,
                    fatigue = 55f + rnd.nextFloat() * 35f,
                    social = 40f + rnd.nextFloat() * 40f,
                    gold = def.gold, affinity = def.affinity,
                )
                for (g in prof.goods) created.stock.add(StockEntry(g.item, g.count))
                save.npcs.add(created)
                created
            }
            val n = Npc(def, prof, state)
            n.homeDoor = door
            n.homeName = home.name
            val ref = def.workplace.ifEmpty { prof.workplace }
            val spot = content.spots[ref]
            val building = if (spot == null && ref.isNotEmpty()) content.buildingFor(ref) else null
            if (spot != null) {
                n.workPos = floatArrayOf(spot.x + hashSide * 1.5f, spot.z + ((def.id.hashCode() shr 8 and 0xFF) / 255f - 0.5f) * 8f)
                n.workGate = spot.gate
                n.workName = spot.name
            } else if (building != null) {
                n.workPos = w.doorOf(building, hashSide * 0.7f)
                n.workName = building.name
            }
            val race = content.races[def.race]
            n.skinHex = def.skin.ifEmpty { race?.skinTones?.firstOrNull() ?: "#D9A77F" }
            n.clothHex = def.cloth.ifEmpty { prof.cloth }
            n.hat = def.hat ?: prof.hat
            n.skin = hexToRgb(n.skinHex)
            n.cloth = hexToRgb(n.clothHex)
            n.hair = hexToRgb(def.hair)
            // Living-world bookkeeping: door spread, need rates, fresh destination cache.
            n.spread = hashSide
            n.spreadAlt = ((def.id.hashCode() shr 8 and 0xFF) / 255f - 0.5f)
            val rates = if (def.needRates.isNotEmpty()) def.needRates else prof.needRates
            n.rates[0] = rates["hunger"] ?: 1f
            n.rates[1] = rates["fatigue"] ?: 1f
            n.rates[2] = rates["social"] ?: 1f
            n.destCache.clear()
            n.decideAt = save.minutes + rnd.nextDouble() * 4.0
            if (state.lastSim <= 0.0 || state.lastSim > save.minutes) state.lastSim = save.minutes
            w.npcs += n
            w.npcById[def.id] = n
        }
    }

    /** Villagers near the player move every frame; the rest are stepped coarsely or abstractly. */
    fun update(dt: Float) {
        val started = System.nanoTime()
        val p = w.p
        for (n in w.npcs) {
            val dx = n.s.x - p.x
            val dz = n.s.z - p.z
            val d2 = dx * dx + dz * dz
            n.tier = when {
                d2 < ACTIVE_RANGE * ACTIVE_RANGE -> 0
                d2 < REGIONAL_RANGE * REGIONAL_RANGE -> 1
                else -> 2
            }
            when (n.tier) {
                0 -> step(n, dt, detailed = true)
                1 -> {
                    n.accum += dt
                    if (n.accum >= 2f) {
                        step(n, n.accum, detailed = false)
                        n.accum = 0f
                    }
                }
                else -> {
                    n.accum += dt
                    if (n.accum >= 5f) {
                        catchUp(n, save.minutes)
                        n.accum = 0f
                    }
                }
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000f
        stepMillis = if (stepMillis <= 0f) millis else stepMillis * 0.9f + millis * 0.1f
    }

    /**
     * Time jumped forward (waiting, sleeping): near and mid-ring villagers keep their normal
     * stepping in the larger steps, distant lives resolve abstractly.
     */
    fun tick(seconds: Float) {
        val p = w.p
        for (n in w.npcs) {
            val dx = n.s.x - p.x
            val dz = n.s.z - p.z
            if (dx * dx + dz * dz < REGIONAL_RANGE * REGIONAL_RANGE) {
                step(n, seconds, detailed = false)
            } else {
                catchUp(n, save.minutes)
            }
        }
    }

    private fun gate(name: String) = if (name == "north") floatArrayOf(0f, -50f) else floatArrayOf(0f, 50f)

    private fun routeTo(n: Npc, tx: Float, tz: Float, targetGate: String) {
        n.path.clear()
        val out = n.s.x * n.s.x + n.s.z * n.s.z > 47f * 47f
        val curGate = if (!out) "" else if (n.s.z > 0f) "south" else "north"
        if (curGate.isNotEmpty() && curGate != targetGate) n.path += gate(curGate)
        if (targetGate.isNotEmpty() && targetGate != curGate) n.path += gate(targetGate)
        n.path += floatArrayOf(tx, tz)
        n.s.indoors = false
    }

    /** The nearest hostile, living creature within [range] metres, or null. */
    private fun threatNear(n: Npc, range: Float): Creature? {
        val r2 = range * range
        for (c in w.creatures) {
            if (!c.def.aggressive || c.s.health <= 0f) continue
            val dx = c.s.x - n.s.x
            val dz = c.s.z - n.s.z
            if (dx * dx + dz * dz <= r2) return c
        }
        return null
    }

    /**
     * The decision, in strict order: danger first, then critical needs, then a failing sense
     * of safety, then the day's schedule, then the old trait-scored routine when no template
     * applies. Each branch sets its own decide time.
     */
    private fun decide(n: Npc) {
        val s = n.s
        val h = Calendar.hour(save.minutes)
        // While fleeing the leash is longer (24 m) than the trigger (16 m), so a wolf at the
        // edge of hearing does not flip the decision on and off.
        val threat = threatNear(n, if (s.action == NpcActions.FLEE) 24f else 16f)
        if (threat != null) {
            if (s.action != NpcActions.FLEE) begin(n, NpcActions.FLEE, h)
            n.decideAt = save.minutes + 2.0
            return
        }
        // Hide at home until the fright fades; the rest branch below keeps them there.
        if (s.action == NpcActions.REST && s.safety < 70f) {
            n.decideAt = save.minutes + 2.0
            return
        }
        if (s.hunger < 20f && s.action != NpcActions.EAT) return beginWith(n, NpcActions.EAT, h)
        if (s.fatigue < 12f && s.action != NpcActions.SLEEP) return beginWith(n, NpcActions.SLEEP, h)
        if (s.safety < 40f && s.action != NpcActions.REST) return beginWith(n, NpcActions.REST, h)
        val entry = w.schedules.entryFor(n, save.minutes)
        if (entry != null) {
            val act = if (entry.activity == "socialize") NpcActions.SOCIAL else entry.activity
            if (act != s.action) {
                begin(n, act, h, entry)
            } else if (act == NpcActions.WANDER && n.path.isEmpty() && n.pause <= 0f && rnd.nextFloat() < 0.5f) {
                begin(n, act, h, entry)
            }
            n.decideAt = save.minutes + 10.0 + rnd.nextDouble() * 8.0
            return
        }
        legacyDecide(n, h)
        n.decideAt = save.minutes + 10.0 + rnd.nextDouble() * 8.0
    }

    private fun beginWith(n: Npc, action: String, h: Float) {
        begin(n, action, h)
        n.decideAt = save.minutes + 10.0 + rnd.nextDouble() * 8.0
    }

    /** The old trait- and need-scored routine, for anyone without a schedule template. */
    private fun legacyDecide(n: Npc, h: Float) {
        val s = n.s
        val night = h >= 21.5f || h < 5.5f
        val working = n.workPos != null && h >= n.prof.workStart && h < n.prof.workEnd
        var best = NpcActions.WANDER
        var bestScore = 0.15f + n.trait("curiosity") * 0.1f + if (s.action == NpcActions.WANDER) 0.12f else 0f
        fun offer(action: String, score: Float) {
            val v = score + if (s.action == action) 0.14f else 0f
            if (v > bestScore) {
                bestScore = v
                best = action
            }
        }
        offer(NpcActions.SLEEP, (1f - s.fatigue / 100f) * (if (night) 1.6f else 0.45f) + if (night) 0.35f else 0f)
        offer(NpcActions.EAT, (1f - s.hunger / 100f) * 1.3f)
        if (working) offer(NpcActions.WORK, 0.5f + n.trait("ambition") * 0.25f)
        offer(NpcActions.SOCIAL, (1f - s.social / 100f) * (0.4f + n.trait("sociability") * 0.6f) * (if (night) 0.3f else 1f))
        if (best != s.action || (n.path.isEmpty() && best == NpcActions.WANDER && rnd.nextFloat() < 0.5f)) begin(n, best, h)
    }

    private fun begin(n: Npc, action: String, h: Float, entry: ScheduleEntryDef? = null) {
        val s = n.s
        if (s.action != action) transitions++
        s.action = action
        n.travelSince = save.minutes
        val target = entry?.target.orEmpty()
        s.actionTarget = target
        when (action) {
            NpcActions.SLEEP -> {
                routeTo(n, n.homeDoor[0], n.homeDoor[1], "")
                n.destName = n.homeName
                n.destLabel = "Sleeping"
            }
            NpcActions.EAT -> {
                val dest = if (target.isNotEmpty()) w.schedules.destinationOf(n, target) else null
                val inn = w.inn
                if (dest != null) {
                    routeTo(n, dest.x, dest.z, dest.gate)
                    n.destName = dest.name
                    n.destLabel = "Eating at ${dest.name}"
                } else if (inn != null && s.gold >= 4 && n.trait("sociability") > 0.5f && n.def.home != inn.id) {
                    val d = w.doorOf(inn, (rnd.nextFloat() - 0.5f) * 8f)
                    routeTo(n, d[0], d[1] + (rnd.nextFloat() - 0.5f) * 2f, "")
                    n.destName = inn.name
                    n.destLabel = "Eating at ${inn.name}"
                    s.gold -= 3
                    w.innkeeper()?.let { it.s.gold += 3 }
                } else {
                    routeTo(n, n.homeDoor[0], n.homeDoor[1], "")
                    n.destName = n.homeName
                    n.destLabel = "Eating at home"
                }
            }
            NpcActions.WORK -> {
                val dest = w.schedules.destinationOf(n, target.ifEmpty { "work" })
                if (dest != null) {
                    routeTo(n, dest.x, dest.z, dest.gate)
                    n.destName = dest.name
                    n.destLabel = n.prof.activity
                } else {
                    routeTo(n, n.homeDoor[0], n.homeDoor[1], "")
                    n.destName = n.homeName
                    n.destLabel = "Working near home"
                }
            }
            NpcActions.REST -> {
                routeTo(n, n.homeDoor[0], n.homeDoor[1], "")
                n.destName = n.homeName
                n.destLabel = "Resting indoors"
            }
            NpcActions.FLEE -> {
                routeTo(n, n.homeDoor[0], n.homeDoor[1], "")
                n.destName = n.homeName
                n.destLabel = "Fleeing home"
            }
            NpcActions.SHOP -> {
                val b = when {
                    target.isNotEmpty() && target != "shop" -> content.buildings[target] ?: content.buildingFor(target)
                    else -> w.shop
                }
                if (b != null) {
                    val d = w.doorOf(b, n.spread)
                    routeTo(n, d[0], d[1], "")
                    n.destName = b.name
                    n.destLabel = "Shopping at ${b.name}"
                } else {
                    failedDests++
                    val fb = entry?.fallback.orEmpty()
                    if (fb.isNotEmpty() && fb != NpcActions.SHOP) begin(n, fb, h) else begin(n, NpcActions.WANDER, h)
                    return
                }
            }
            NpcActions.PATROL -> {
                val dest = if (target.isNotEmpty()) w.schedules.destinationOf(n, target) else null
                if (dest != null) {
                    n.patrolLeg = 0
                    routeTo(n, dest.x, dest.z, dest.gate)
                    n.destName = dest.name
                    n.destLabel = "Patrolling to ${dest.name}"
                } else {
                    failedDests++
                    begin(n, NpcActions.WANDER, h)
                    return
                }
            }
            NpcActions.SOCIAL -> {
                val inn = w.inn
                if (h >= 18f && inn != null) {
                    val d = w.doorOf(inn, (rnd.nextFloat() - 0.5f) * 9f)
                    routeTo(n, d[0] - 1.5f, d[1], "")
                    n.destName = inn.name
                    n.destLabel = "Sharing a drink outside ${inn.name}"
                } else {
                    val a = rnd.nextFloat() * TWO_PI
                    val r = 3f + rnd.nextFloat() * 6f
                    routeTo(n, cos(a) * r, 2f + sin(a) * r, "")
                    n.destName = "the square"
                    n.destLabel = "Chatting in the square"
                }
                n.nextSocialAt = save.minutes + 10.0
            }
            else -> {
                val a = rnd.nextFloat() * TWO_PI
                val r = 6f + rnd.nextFloat() * 20f
                routeTo(n, cos(a) * r, sin(a) * r, "")
                n.destName = "the village"
                n.destLabel = "Taking the air"
            }
        }
        val far = n.path.size > 1
        s.activity = if (far) "Heading to ${n.destName}" else "Walking to ${n.destName}"
    }

    fun step(n: Npc, dt: Float, detailed: Boolean) {
        val s = n.s
        val hours = dt * MIN_PER_SEC / 60f

        // Needs wear away, scaled by the person's and their trade's constitution. Safety rises
        // gently when all is calm and falls sharply near a hostile beast.
        s.hunger = max(0f, s.hunger - 5f * n.rates[0] * hours)
        s.fatigue = max(0f, s.fatigue - 4.2f * n.rates[1] * hours)
        s.social = max(0f, s.social - 3f * n.rates[2] * hours)
        val threatened = threatNear(n, 16f) != null
        s.safety = if (threatened) max(0f, s.safety - 40f * hours)
        else min(100f, s.safety + (if (s.indoors) 4f else 1.5f) * hours)

        if (save.minutes >= n.decideAt) decide(n)

        // A walk that outlives its purpose: a crossing of the vale takes under an hour, so one
        // that runs longer is stuck, and the person sets out afresh.
        if (n.path.isNotEmpty() && n.travelSince > 0.0 && save.minutes - n.travelSince > 60.0) {
            val dest = if (s.actionTarget.isNotEmpty()) w.schedules.destinationOf(n, s.actionTarget) else null
            if (dest != null) {
                routeTo(n, dest.x, dest.z, dest.gate)
                n.travelSince = save.minutes
            } else {
                begin(n, s.action.ifEmpty { NpcActions.WANDER }, Calendar.hour(save.minutes))
            }
        }

        n.moving = false
        if (n.pause > 0f) {
            n.pause -= dt
            return
        }
        val speed = if (s.action == NpcActions.FLEE) 2.6f else 1.7f - (n.def.age - 30).coerceAtLeast(0) * 0.008f
        var remaining = speed * dt
        while (remaining > 0f && n.path.isNotEmpty()) {
            val wp = n.path.first()
            val dx = wp[0] - s.x
            val dz = wp[1] - s.z
            val dist = sqrt(dx * dx + dz * dz)
            if (dist <= remaining || dist < 0.05f) {
                s.x = wp[0]
                s.z = wp[1]
                remaining -= dist
                n.path.removeFirst()
            } else {
                s.x += dx / dist * remaining
                s.z += dz / dist * remaining
                n.yaw = atan2(dx, -dz)
                n.moving = true
                remaining = 0f
            }
        }
        if (n.moving) {
            if (detailed) n.phase += dt * 7f
            s.lastSim = save.minutes
            return
        }
        if (n.path.isNotEmpty()) return

        // Arrived: carry out the chosen activity.
        s.activity = n.destLabel.ifEmpty { "Idle" }
        when (s.action) {
            NpcActions.SLEEP -> {
                s.indoors = true
                s.fatigue = min(100f, s.fatigue + 17f * hours)
                s.safety = min(100f, s.safety + 8f * hours)
                if (s.fatigue >= 98f) n.decideAt = min(n.decideAt, save.minutes + 1.0)
            }
            NpcActions.EAT -> {
                s.indoors = n.destName == n.homeName
                s.hunger = min(100f, s.hunger + 65f * hours)
                if (s.hunger >= 96f) n.decideAt = min(n.decideAt, save.minutes + 0.5)
            }
            NpcActions.WORK -> {
                n.wageAcc += n.prof.wage * hours
                if (n.wageAcc >= 1f) {
                    val coins = n.wageAcc.toInt()
                    s.gold += coins
                    n.wageAcc -= coins
                }
                if (detailed) n.yaw = atan2(-s.x, s.z)
                // Work shared is acquaintance made: a light, cooled check, never a scan every frame.
                if (save.minutes >= n.nextWorkCheck) {
                    n.nextWorkCheck = save.minutes + 120.0
                    w.npcs.firstOrNull { o ->
                        o !== n && o.s.action == NpcActions.WORK && o.path.isEmpty() && o.workName == n.workName &&
                            abs(o.s.x - s.x) < 9f && abs(o.s.z - s.z) < 9f
                    }?.let { w.social.sharedWork(n, it) }
                }
            }
            NpcActions.SOCIAL -> {
                s.social = min(100f, s.social + 30f * hours)
                if (save.minutes >= n.nextSocialAt) {
                    n.nextSocialAt = save.minutes + 50.0
                    mingle(n)
                }
            }
            NpcActions.REST -> {
                s.indoors = true
                s.fatigue = min(100f, s.fatigue + 8f * hours)
                s.safety = min(100f, s.safety + 8f * hours)
                if (s.safety >= 70f && s.fatigue >= 95f) n.decideAt = min(n.decideAt, save.minutes + 1.0)
            }
            NpcActions.FLEE -> {
                s.indoors = true
                s.safety = min(100f, s.safety + 8f * hours)
            }
            NpcActions.SHOP -> {
                s.hunger = min(100f, s.hunger + 25f * hours)
                if (s.gold >= 2) {
                    s.gold -= 2
                    n.wageAcc = 0f
                }
                n.pause = 6f + rnd.nextFloat() * 6f
            }
            NpcActions.PATROL -> {
                n.pause = 2f + rnd.nextFloat() * 3f
                n.patrolLeg += 1
                val dest = if (n.patrolLeg % 2 == 0) w.schedules.destinationOf(n, s.actionTarget) else Dest(0f, 2f, "", "the square")
                if (dest != null) {
                    routeTo(n, dest.x, dest.z, dest.gate)
                    n.destName = dest.name
                    n.destLabel = if (n.patrolLeg % 2 == 0) "Patrolling to ${dest.name}" else "Patrolling the square"
                    s.activity = "Walking to ${n.destName}"
                    n.travelSince = save.minutes
                }
            }
        }
        s.lastSim = save.minutes
    }

    /**
     * Brings a far-off life up to the present without touching the frame loop. Needs resolve
     * analytically in bounded chunks, the dominant action is re-derived from the clock each
     * chunk, and the person is finally placed where that action leads.
     *
     * This snapping is an abstraction for unseen lives only, never a visible movement: the
     * moment a villager comes within the mid ring the regular tiers take over, and a walking
     * person seen from nearby is always moved by [step].
     */
    fun catchUp(n: Npc, now: Double) {
        val s = n.s
        var from = if (s.lastSim <= 0.0 || s.lastSim > now) now - 5.0 else s.lastSim
        if (now - from > 120.0) from = now - 120.0
        var action = s.action.ifEmpty { NpcActions.WANDER }
        var entry: ScheduleEntryDef? = null
        while (from < now) {
            val chunk = min(30.0, now - from)
            val hours = (chunk / 60.0).toFloat()
            s.hunger = max(0f, s.hunger - 5f * n.rates[0] * hours)
            s.fatigue = max(0f, s.fatigue - 4.2f * n.rates[1] * hours)
            s.social = max(0f, s.social - 3f * n.rates[2] * hours)
            s.safety = min(100f, s.safety + 2f * hours)
            val resolved = resolveFor(n, from + chunk / 2.0)
            action = resolved.first
            entry = resolved.second
            catchUpEffect(n, action, hours)
            from += chunk
        }
        s.action = action
        s.actionTarget = entry?.target.orEmpty()
        n.path.clear()
        n.moving = false
        val dest = when (action) {
            NpcActions.SLEEP, NpcActions.REST, NpcActions.FLEE -> Dest(n.homeDoor[0], n.homeDoor[1], "", n.homeName)
            NpcActions.EAT -> w.schedules.destinationOf(n, s.actionTarget.ifEmpty { "home" })
                ?: Dest(n.homeDoor[0], n.homeDoor[1], "", n.homeName)
            NpcActions.WORK -> w.schedules.workDest(n)
            NpcActions.SHOP -> w.shop?.let { w.doorOf(it, n.spread).let { d -> Dest(d[0], d[1], "", it.name) } }
            else -> null // wanderers and idlers are left where their lives left them
        }
        if (dest != null) {
            s.x = dest.x
            s.z = dest.z
            s.indoors = action == NpcActions.SLEEP || action == NpcActions.REST
            n.destName = dest.name
        }
        s.activity = when (action) {
            NpcActions.SLEEP -> "Sleeping"
            NpcActions.EAT -> if (n.destName == n.homeName) "Eating at home" else "Eating at ${n.destName}"
            NpcActions.WORK -> n.prof.activity
            NpcActions.REST -> "Resting indoors"
            NpcActions.SOCIAL -> "Chatting"
            NpcActions.SHOP -> "Shopping"
            NpcActions.PATROL -> "Patrolling"
            else -> "Taking the air"
        }
        s.lastSim = now
        n.decideAt = max(n.decideAt, save.minutes + rnd.nextDouble() * 4.0)
    }

    /** What a distant life should be doing at [minutes]: critical needs, then the schedule, then a simple clock routine. */
    private fun resolveFor(n: Npc, minutes: Double): Pair<String, ScheduleEntryDef?> {
        if (n.s.hunger < 20f) return NpcActions.EAT to null
        if (n.s.fatigue < 12f) return NpcActions.SLEEP to null
        val entry = w.schedules.entryFor(n, minutes)
        if (entry != null) {
            val act = if (entry.activity == "socialize") NpcActions.SOCIAL else entry.activity
            return act to entry
        }
        val h = Calendar.hour(minutes)
        val night = h >= 21.5f || h < 5.5f
        return when {
            night -> NpcActions.SLEEP to null
            n.workPos != null && h >= n.prof.workStart && h < n.prof.workEnd -> NpcActions.WORK to null
            n.s.social < 40f -> NpcActions.SOCIAL to null
            else -> NpcActions.WANDER to null
        }
    }

    private fun catchUpEffect(n: Npc, action: String, hours: Float) {
        val s = n.s
        when (action) {
            NpcActions.SLEEP -> {
                s.fatigue = min(100f, s.fatigue + 17f * hours)
                s.safety = min(100f, s.safety + 8f * hours)
            }
            NpcActions.EAT -> s.hunger = min(100f, s.hunger + 65f * hours)
            NpcActions.REST, NpcActions.FLEE -> {
                s.fatigue = min(100f, s.fatigue + 8f * hours)
                s.safety = min(100f, s.safety + 8f * hours)
            }
            NpcActions.SOCIAL -> {
                s.social = min(100f, s.social + 30f * hours)
                w.social.abstractLife(n, action, hours)
            }
            NpcActions.SHOP -> s.hunger = min(100f, s.hunger + 25f * hours)
            NpcActions.WORK -> {
                n.wageAcc += n.prof.wage * hours
                if (n.wageAcc >= 1f) {
                    val coins = n.wageAcc.toInt()
                    s.gold += coins
                    n.wageAcc -= coins
                }
                w.social.abstractLife(n, action, hours)
            }
        }
    }

    /** Two townsfolk who are both socialising nearby talk, quarrel, and pass the news along. */
    private fun mingle(n: Npc) {
        val other = w.npcs.firstOrNull {
            it !== n && it.s.action == NpcActions.SOCIAL && it.path.isEmpty() &&
                abs(it.s.x - n.s.x) < 12f && abs(it.s.z - n.s.z) < 12f
        } ?: return
        w.social.encounter(n, other)
        n.yaw = atan2(other.s.x - n.s.x, -(other.s.z - n.s.z))
        other.yaw = atan2(n.s.x - other.s.x, -(n.s.z - other.s.z))
    }

    /** Publishes the simulation's shape for the diagnostics report. */
    fun pushDiagnostics() {
        var near = 0
        var regional = 0
        var distant = 0
        var working = 0
        var sleeping = 0
        var travelling = 0
        var fleeing = 0
        for (n in w.npcs) {
            when (n.tier) {
                0 -> near++
                1 -> regional++
                else -> distant++
            }
            when (n.s.action) {
                NpcActions.WORK -> working++
                NpcActions.SLEEP -> sleeping++
                NpcActions.FLEE -> fleeing++
            }
            if (n.path.isNotEmpty()) travelling++
        }
        val so = w.social
        Diagnostics.updateSim { it.copy(
            npcs = w.npcs.size, active = near, regional = regional, distant = distant,
            working = working, sleeping = sleeping, travelling = travelling, fleeing = fleeing,
            transitions = transitions, failedDestinations = failedDests, stepMillis = stepMillis,
            relationships = save.social.relationships.size, relChanges = so.relChanges,
            memories = save.npcs.sumOf { it.experiences.size },
            memoriesCreated = so.memoriesCreated, memoriesPruned = so.memoriesPruned,
            gossip = save.social.gossip.size, gossipCreated = so.gossipCreated,
            gossipShared = so.gossipShared, gossipRejected = so.gossipRejected,
            socialAttempts = so.socialAttempts, socialCompleted = so.socialCompleted,
            socialSkipped = so.socialSkipped, invalidRefs = so.invalidRefs,
        ) }
    }

    companion object {
        /** Tier boundaries in metres from the player. */
        const val ACTIVE_RANGE = 70f
        const val REGIONAL_RANGE = 150f
    }
}
