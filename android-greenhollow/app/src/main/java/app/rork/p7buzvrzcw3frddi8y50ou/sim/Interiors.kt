package app.rork.p7buzvrzcw3frddi8y50ou.sim

import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.ContentRegistry
import app.rork.p7buzvrzcw3frddi8y50ou.data.LogicalBuilding
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.STORY
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.TILE_SIZE
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Tile
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Building types that are features of the open air, not places one walks into. */
private val NO_INTERIOR = setOf("well", "shrine")

/**
 * One way in and out of a building: a stable connection between a point in the overworld and
 * a spawn point in the building's interior instance. Every enterable building derives its
 * entrances the same generic way — from the ground-floor door tiles of its (cached, seeded)
 * tile plan — so procedural manors and hand-authored cottages need no per-building code.
 * A plan with a front and a back door yields two entrances; each is bidirectional.
 */
class Entrance(
    /** Unique: "ent:<building id>:<side>". */
    val id: String,
    val buildingId: String,
    /** front, back, east or west — read off which side of the door tile lies outside. */
    val side: String,
    /** World-space position just outside the door, [outYaw] facing the doorway. */
    val outX: Float,
    val outZ: Float,
    val outYaw: Float,
    /** Interior-local spawn position, [inYaw] facing into the building. */
    val inX: Float,
    val inZ: Float,
    val inYaw: Float,
) {
    val instanceId: String get() = "${LogicalBuilding.INTERIOR_PREFIX}$buildingId"
}

/**
 * Resolves every building's entrance connections. Derived deterministically from content and
 * the cached generator, so the same pack always grows the same doorways; results are cached
 * per building, and nothing here touches rendering or per-frame work.
 */
class EntranceRegistry(private val content: ContentRegistry) {
    private val cache = HashMap<String, List<Entrance>>()

    /** The entrances of one building; empty when it has no usable interior plan. */
    fun entrances(buildingId: String): List<Entrance> {
        cache[buildingId]?.let { return it }
        val derived = derive(content.buildings[buildingId] ?: return emptyList())
        cache[buildingId] = derived
        return derived
    }

    /** Every entrance in the world, for interaction scans and tests. */
    fun all(): List<Entrance> = content.buildings.keys.flatMap { entrances(it) }

    fun byId(entranceId: String): Entrance? = all().firstOrNull { it.id == entranceId }

    private fun derive(def: BuildingDef): List<Entrance> {
        if (def.type in NO_INTERIOR) return emptyList()
        val plan = BuildingCache.get(def) ?: return emptyList()
        val ground = plan.floors.firstOrNull() ?: return emptyList()
        val tiles = ground.tiles
        val cx = (plan.width - 1) / 2f
        val cz = (plan.height - 1) / 2f
        val yaw = atan2(def.x, def.z)
        val c = cos(yaw)
        val s = sin(yaw)

        fun local(tx: Int, ty: Int) = (tx - cx) * TILE_SIZE to (cz - ty) * TILE_SIZE

        fun world(lx: Float, lz: Float) = def.x + lx * c + lz * s to def.z - lx * s + lz * c

        val out = ArrayList<Entrance>()
        for (ty in tiles.indices) {
            for (tx in tiles[ty].indices) {
                if (tiles[ty][tx] != Tile.DOOR) continue
                // An entrance door is a ground-floor door with open air on one side.
                val outside = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).firstOrNull { (dx, dy) ->
                    tiles.getOrNull(ty + dy)?.getOrNull(tx + dx) == Tile.EMPTY
                } ?: continue
                val side = when (outside) {
                    0 to 1 -> "front"; 0 to -1 -> "back"; 1 to 0 -> "east"; else -> "west"
                }
                // Outward/inward in local space, then through the building's door-facing turn.
                val odx = outside.first * TILE_SIZE
                val odz = -outside.second * TILE_SIZE
                val wdx = odx * c + odz * s
                val wdz = -odx * s + odz * c
                val (dwx, dwz) = world(local(tx, ty).first, local(tx, ty).second)
                // The inside spawn stays in the interior's own local space; the outside point
                // is a real place in the overworld, through the building's door-facing turn.
                val inLocal = local(tx, ty)
                out += Entrance(
                    id = "ent:${def.id}:$side",
                    buildingId = def.id,
                    side = side,
                    outX = dwx + wdx,
                    outZ = dwz + wdz,
                    outYaw = atan2(-wdx, wdz),
                    inX = inLocal.first - odx * 0.2f,
                    inZ = inLocal.second - odz * 0.2f,
                    inYaw = atan2(wdx, -wdz),
                )
            }
        }
        return out
    }
}

/**
 * The currently loaded interior instance: the building's cached plan turned into local-space
 * collision and (by the renderer, on demand) its own mesh. Nothing here is the overworld —
 * the plan never enters [app.rork.p7buzvrzcw3frddi8y50ou.render.WorldMesh].
 */
class ActiveInterior(
    val instanceId: String,
    val buildingId: String,
    val plan: Building,
) {
    /** Collision boxes in interior-local space, tagged with the floor they belong to. */
    val boxes = ArrayList<Pair<Int, Box>>()

    fun floorIndexOf(y: Float): Int =
        (((y + 0.5f) / STORY).toInt()).coerceIn(0, plan.floors.size - 1)
}

/**
 * The interior-instance lifecycle: generate, load, activate, and unload — with one active
 * interior at a time, so entering different buildings never accumulates geometry in memory.
 * Unloading drops meshes and collision but never persistent state: layouts are re-derived
 * from the cached deterministic plans, and the save keeps only identities and references.
 */
class InteriorSystem(private val w: World) {
    private val save get() = w.save
    private val p get() = w.p
    val entrances = EntranceRegistry(w.content)

    /** The one interior the player is inside, or null outdoors. */
    var active: ActiveInterior? = null
        private set

    /** Which entrance the player came in by — the way back they'll be offered first. */
    var enteredVia: Entrance? = null
        private set

    // ---------------------------------------------------------------- lifecycle

    /**
     * Steps through a door: remembers where the player stood, activates the interior instance
     * (plan from the deterministic cache, collision rebuilt), and moves the player to the
     * doorway's interior spawn point in the interior's own coordinate space.
     */
    fun enter(entrance: Entrance) {
        val def = w.content.buildings[entrance.buildingId] ?: return
        if (active?.instanceId == entrance.instanceId) return
        val plan = BuildingCache.get(def) ?: run {
            w.toast("The door of ${def.name} will not open.")
            return
        }
        // Return info is captured only when outdoors; inside, the first way out is kept.
        if (active == null && p.location.isEmpty()) {
            p.returnX = p.x; p.returnY = p.y; p.returnZ = p.z; p.returnYaw = p.yaw
        }
        activate(entrance.instanceId, entrance.buildingId, plan)
        enteredVia = entrance
        p.location = entrance.instanceId
        p.x = entrance.inX
        p.z = entrance.inZ
        p.y = groundY(p.x, p.z, 0f)
        w.vy = 0f
        w.grounded = true
        p.yaw = entrance.inYaw
        rememberVisit(entrance.instanceId, entrance.buildingId, plan.seed)
        w.toast("You enter ${def.name}.")
        w.journalDirty = true
    }

    fun enterByEntranceId(entranceId: String): Boolean {
        val e = entrances.byId(entranceId) ?: return false
        enter(e)
        return true
    }

    /**
     * Steps back out through a door (the nearest one when the player has walked to another
     * exit). The player returns to where they stepped inside from — the saved return info —
     * or, if that was somehow lost, just outside the door they leave by. Never strands.
     */
    fun exit(entrance: Entrance? = null) {
        val a = active ?: return
        val door = entrance ?: entrances.entrances(a.buildingId)
            .minByOrNull { (it.inX - p.x) * (it.inX - p.x) + (it.inZ - p.z) * (it.inZ - p.z) }
        if (door == null) {
            // No door resolves (the pack changed underneath us): put the player back anyway.
            p.x = p.returnX; p.z = p.returnZ
            p.y = maxOf(p.returnY, Terrain.height(p.x, p.z))
            p.location = ""
            deactivate()
            return
        }
        if (p.returnX != 0f || p.returnZ != 0f) {
            p.x = p.returnX; p.z = p.returnZ
            p.y = maxOf(p.returnY, Terrain.height(p.x, p.z))
            p.yaw = p.returnYaw
        } else {
            p.x = door.outX; p.z = door.outZ
            p.y = Terrain.height(door.outX, door.outZ)
            p.yaw = door.outYaw + TWO_PI / 2f
        }
        p.location = ""
        deactivate()
        w.journalDirty = true
    }

    /** Re-activates the interior a loaded save was written inside. Called once at world start. */
    fun restore() {
        val loc = p.location
        if (loc.isEmpty()) return
        val buildingId = loc.removePrefix(LogicalBuilding.INTERIOR_PREFIX)
        val def = w.content.buildings[buildingId]
        val plan = def?.let { BuildingCache.get(it) }
        if (def == null || plan == null) {
            p.location = ""
            if (p.returnX == 0f && p.returnZ == 0f) {
                p.x = 0f; p.z = 122f; p.yaw = 0f
            } else {
                p.x = p.returnX; p.z = p.returnZ; p.yaw = p.returnYaw
            }
            p.y = maxOf(p.returnY, Terrain.height(p.x, p.z))
            Diagnostics.record("interiors", "The player was saved inside a building no loaded pack defines; they were placed back outside.")
            return
        }
        activate(loc, buildingId, plan)
    }

    private fun activate(instanceId: String, buildingId: String, plan: Building) {
        val a = ActiveInterior(instanceId, buildingId, plan)
        buildCollision(a)
        active = a
    }

    private fun deactivate() {
        active = null
        enteredVia = null
        // Meshes and collision die with the slot; the plan itself stays in the deterministic cache.
    }

    private fun rememberVisit(instanceId: String, buildingId: String, seed: Long) {
        val rec = save.interiors[instanceId] ?: InteriorSave(
            instanceId = instanceId,
            buildingId = buildingId,
            seed = seed,
        ).also { save.interiors[it.instanceId] = it }
        rec.lastVisited = save.minutes
        rec.visits++
    }

    // ---------------------------------------------------------------- collision

    /** Solid tiles (walls, posts, sealed doorways, the outside ring) become boxes, per floor. */
    private fun buildCollision(a: ActiveInterior) {
        a.boxes.clear()
        for (f in a.plan.floors) {
            val tiles = f.tiles
            for (ty in tiles.indices) {
                for (tx in tiles[ty].indices) {
                    val t = tiles[ty][tx]
                    if (isWalkableTile(t)) continue
                    val solidNeighbour = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) ->
                        tiles.getOrNull(ty + dy)?.getOrNull(tx + dx)?.let(::isWalkableTile) == true
                    }
                    if (!solidNeighbour) continue
                    val lx = (tx - (a.plan.width - 1) / 2f) * TILE_SIZE
                    val lz = ((a.plan.height - 1) / 2f - ty) * TILE_SIZE
                    a.boxes += f.level to Box(lx, lz, TILE_SIZE / 2f, TILE_SIZE / 2f, 1f, 0f)
                }
            }
        }
    }

    private fun isWalkableTile(t: Int): Boolean =
        t == Tile.FLOOR || t == Tile.DOOR || t == Tile.STAIRS || t == Tile.BALCONY

    /** Pushes a position (interior-local x/z) out of the active interior's walls, one floor at a time. */
    fun collide(pos: FloatArray, radius: Float) {
        val a = active ?: return
        val floor = a.floorIndexOf(p.y)
        for ((level, b) in a.boxes) {
            if (level != floor) continue
            val dx = pos[0] - b.cx
            val dz = pos[1] - b.cz
            if (kotlin.math.abs(dx) > 2f || kotlin.math.abs(dz) > 2f) continue
            val px = b.hw + radius - kotlin.math.abs(dx)
            val pz = b.hd + radius - kotlin.math.abs(dz)
            if (px > 0f && pz > 0f) {
                if (px < pz) {
                    pos[0] += if (dx >= 0f) px else -px
                } else {
                    pos[1] += if (dz >= 0f) pz else -pz
                }
            }
        }
    }

    // ---------------------------------------------------------------- ground

    /**
     * The walkable surface under a point, given the height the player is at now — floor slabs,
     * stair steps (rising along +x of the stair block), all in interior-local space. Of the
     * floors that stack at one tile, the surface nearest the player's height wins, so a
     * stairwell serves the floor being climbed and never yanks the player up through the one
     * above. Used as the ground height while indoors, exactly as Terrain.height is outdoors.
     */
    fun groundY(x: Float, z: Float, y: Float): Float {
        val a = active ?: return 0f
        val cx = (a.plan.width - 1) / 2f
        val cz = (a.plan.height - 1) / 2f
        val tx = Math.round(x / TILE_SIZE + cx)
        val ty = Math.round(cz - z / TILE_SIZE)
        var best = Float.NaN
        var bestDist = Float.MAX_VALUE
        var anySurface = 0.12f
        for (f in a.plan.floors) {
            val t = f.tiles.getOrNull(ty)?.getOrNull(tx)
            if (t == null || !isWalkableTile(t)) continue
            val surface = f.level * STORY + if (t == Tile.STAIRS) {
                val col = (tx - a.plan.stairs.x).coerceIn(0, 2)
                STORY * (col + 1) / 3f
            } else {
                0.12f
            }
            val d = kotlin.math.abs(surface - y)
            if (d < bestDist) {
                bestDist = d
                anySurface = surface
            }
            if (d <= 1.3f && (best.isNaN() || d < bestDist)) best = surface
        }
        return if (best.isNaN()) anySurface else best
    }

    /** The middle of the ground-floor hallway in local space — where a building's services sit. */
    fun serviceSpot(a: ActiveInterior): FloatArray {
        val ground = a.plan.floors.first()
        val rect = ground.rect
        val cy = rect.y + rect.h / 2
        val cx = rect.x + rect.w / 2
        // The hall runs the full length; walk out from the middle until a walkable tile turns up.
        for (ring in 0..2) {
            for (cand in intArrayOf(0, 1, -1, 2, -2)) {
                val ty = cy + cand * (ring + 1).coerceAtMost(2) / 2
                val t = ground.tiles.getOrNull(ty)?.getOrNull(cx)
                if (t != null && isWalkableTile(t)) {
                    return floatArrayOf(
                        (cx - (a.plan.width - 1) / 2f) * TILE_SIZE,
                        ((a.plan.height - 1) / 2f - ty) * TILE_SIZE,
                    )
                }
            }
        }
        return floatArrayOf(0f, 0f)
    }

    // ---------------------------------------------------------------- association

    /**
     * The lives that call this building home or workplace. The existing `indoors` flag stays an
     * exterior-facing marker (villagers wait at their door); this is the clean hook future
     * interior NPC routines will walk through, and lets the journal and tests reason about
     * who belongs to an interior without duplicating home/work references.
     */
    fun occupantsOf(buildingId: String): List<Npc> = w.npcs.filter {
        it.def.home == buildingId || it.prof.workplace == buildingId
    }
}
