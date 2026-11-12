package app.rork.p7buzvrzcw3frddi8y50ou.data

import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.TILE_SIZE
import kotlinx.serialization.Serializable

/**
 * Building roadmap Phase 1A — semantic layer over [BuildingDef].
 *
 * This file adds *meaning* to buildings; it does not change how they are built, rendered,
 * collided or simulated. Three ideas:
 *
 *  1. [BuildingTaxonomy] — the closed vocabularies (classifications, functional zones, access
 *     levels), the legacy `BuildingDef.type` → classification mapping, and the generated
 *     room-purpose → zone mapping. Unknown words never guess; they resolve to "" and are
 *     reported by [ContentBuilder].
 *  2. [LogicalBuilding] — one stable logical identity per building, with TES-style derived
 *     instance references for its exterior ("ext:<id>") and future interior ("int:<id>").
 *     These are properties computed from the id, not persistent data: nothing here loads an
 *     interior yet.
 *  3. [BuildingDirectory] — resolves a building id to its [LogicalBuilding] with [RoomMeta]
 *     rooms. Procedural buildings derive rooms from their generated floor plans (floor-qualified
 *     refs, so the same local room number on different floors cannot collide); hand-authored
 *     buildings use the rooms their pack declares. Legacy buildings with no metadata get none —
 *     no rooms are ever fabricated.
 *
 * Everything is deterministic and derived from content + the (cached, seeded) generator, so
 * saves stay at their current version with no migration.
 */
object BuildingTaxonomy {
    /** The closed set of building classifications a pack may declare. */
    val CLASSIFICATIONS = setOf(
        "residential", "commercial", "industrial", "hospitality",
        "civic", "religious", "agricultural", "storage", "mixed_use",
    )

    /** The closed set of functional zones a room or building may use. */
    val ZONES = setOf("residential", "commercial", "workshop", "storage", "public", "private", "staff_only")

    /** Access levels a room may declare; "" means unrestricted (the usual public ground floor). */
    val ACCESS_LEVELS = setOf("public", "private", "staff_only")

    /**
     * Legacy [BuildingDef.type] words mapped to their primary classification. A building that
     * declares no explicit classification gets its type's word here; a type outside this map
     * leaves the building unclassified (""), reported, never guessed.
     */
    private val LEGACY_TYPES = mapOf(
        "house" to "residential",
        "cottage" to "residential",
        "manor" to "residential",
        "farmhouse" to "agricultural",
        "barn" to "agricultural",
        "shop" to "commercial",
        "market" to "commercial",
        "apothecary" to "commercial",
        "smithy" to "industrial",
        "workshop" to "industrial",
        "mill" to "industrial",
        "inn" to "hospitality",
        "tavern" to "hospitality",
        "hall" to "civic",
        "well" to "civic",
        "chapel" to "religious",
        "temple" to "religious",
        "shrine" to "religious",
        "warehouse" to "storage",
        "granary" to "storage",
    )

    fun classifyType(type: String): String? = LEGACY_TYPES[type.trim().lowercase()]

    /** Generated room purposes (the generator's tile kinds) mapped to a functional zone. */
    private val PURPOSE_ZONES = mapOf(
        "bedchamber" to "residential",
        "guest chamber" to "residential",
        "nursery" to "residential",
        "solar" to "private",
        "study" to "private",
        "pantry" to "storage",
        "storeroom" to "storage",
        "larder" to "storage",
        "scullery" to "storage",
        "cold cellar" to "storage",
        "buttery" to "storage",
        "linen room" to "storage",
        "armory" to "storage",
        "kitchen" to "workshop",
        "workshop" to "workshop",
        "scriptorium" to "workshop",
        "great hall" to "public",
        "entry hall" to "public",
        "parlor" to "public",
        "common room" to "public",
        "guardroom" to "staff_only",
    )

    /** The zone a generated room purpose falls in; "" when the purpose has none — no guessing. */
    fun zoneForPurpose(purpose: String): String = PURPOSE_ZONES[purpose.trim().lowercase()] ?: ""
}

/** A world-space rectangle (tiles × [TILE_SIZE] multiplied out), used for authored room bounds. */
@Serializable
data class Bounds(val x: Float, val z: Float, val w: Float, val d: Float)

/**
 * One room of a [LogicalBuilding], in the common representation both generated and authored
 * rooms normalise to. [ref] is stable and floor-qualified for procedural rooms
 * ("<id>#f<floor>#r<local>"); authored refs are "<id>#room:<id>".
 */
data class RoomMeta(
    val ref: String,
    val floor: Int,
    /** Generated tile kind, or the pack's free-text purpose for authored rooms. */
    val purpose: String,
    /** Functional zone; "" when unmapped/undeclared — never guessed. */
    val zone: String,
    /** "", "public", "private" or "staff_only". */
    val access: String,
    /** World-space bounds when known (generated rooms always; authored rooms when declared). */
    val bounds: Bounds?,
)

/**
 * The logical identity of a building: what it *is* (classification), what mixture of activity
 * it holds ([zones], the union of declared building zones and room zones), and its rooms.
 * Distinct exterior and interior instance references share the logical id, TES-style; they are
 * derived properties — this milestone defines the architecture, it does not yet load interiors.
 */
data class LogicalBuilding(
    val id: String,
    val classification: String,
    val zones: Set<String>,
    val rooms: List<RoomMeta>,
    val procedural: Boolean,
) {
    val exteriorInstanceId: String get() = "$EXTERIOR_PREFIX$id"
    val interiorInstanceId: String get() = "$INTERIOR_PREFIX$id"

    companion object {
        const val EXTERIOR_PREFIX = "ext:"
        const val INTERIOR_PREFIX = "int:"
    }
}

/**
 * Resolves building ids to [LogicalBuilding]s. Procedural room data comes from the cached,
 * deterministic generator; authored room data from the (validated) definition. Lookups are
 * cached, so generation happens at most once per building.
 */
class BuildingDirectory(private val content: ContentRegistry) {
    private val cache = HashMap<String, LogicalBuilding?>()

    fun logical(id: String): LogicalBuilding? {
        cache[id]?.let { return it }
        val def = content.buildings[id] ?: return null
        val rooms = ArrayList<RoomMeta>()
        if (def.procedural) {
            val plan = BuildingCache.get(def)
            if (plan != null) {
                for (floor in plan.floors) {
                    for (room in floor.rooms) {
                        rooms += RoomMeta(
                            ref = "${def.id}#f${floor.level}#r${room.id}",
                            floor = floor.level,
                            purpose = room.kind,
                            zone = BuildingTaxonomy.zoneForPurpose(room.kind),
                            access = "",
                            bounds = Bounds(
                                room.rect.x * TILE_SIZE,
                                room.rect.y * TILE_SIZE,
                                room.rect.w * TILE_SIZE,
                                room.rect.h * TILE_SIZE,
                            ),
                        )
                    }
                }
            }
        } else {
            for (r in def.rooms) {
                rooms += RoomMeta(
                    ref = "${def.id}#room:${r.id}",
                    floor = r.floor,
                    purpose = r.purpose,
                    zone = r.zone,
                    access = r.access,
                    bounds = r.bounds,
                )
            }
        }
        val zones = LinkedHashSet(def.zones)
        for (r in rooms) if (r.zone.isNotEmpty()) zones.add(r.zone)
        val logical = LogicalBuilding(def.id, def.classification, zones, rooms, def.procedural)
        cache[def.id] = logical
        return logical
    }
}
