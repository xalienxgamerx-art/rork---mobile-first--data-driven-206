package app.rork.p7buzvrzcw3frddi8y50ou.procgen

import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef

/**
 * Grows each procedural building once and keeps it. Generation runs only when a building
 * definition first appears (world start, pack change); afterwards everyone — renderer,
 * collision, diagnostics — reads the same cached plan, so a seed never grows two buildings.
 */
object BuildingCache {

    class Summary(
        val id: String,
        val name: String,
        val seed: Long,
        val floors: Int,
        val attempts: Int,
        val sound: Boolean,
        val problems: String,
        val building: Building?,
    )

    private val cache = HashMap<String, Building>()

    /** One entry per procedural building definition, for the diagnostics report. */
    val summaries = LinkedHashMap<String, Summary>()

    /**
     * A building without an explicit seed still gets a stable one, derived from its id —
     * the same pack always grows the same hall, from session to session and device to device.
     */
    fun seedFor(def: BuildingDef): Long =
        if (def.genSeed != 0L) def.genSeed
        else (def.id.fold(2166136261L) { acc, ch -> (acc xor ch.code.toLong()) * 16777619L } and 0x7FFFFFFFL)
            .coerceAtLeast(1L)

    fun options(def: BuildingDef) = BuildingOptions(
        seed = seedFor(def),
        floors = def.genFloors.takeIf { it > 0 },
        width = def.genWidth.takeIf { it > 0 },
        depth = def.genDepth.takeIf { it > 0 },
    )

    private fun key(def: BuildingDef) =
        "${def.id}|${def.genSeed}|${def.genFloors}|${def.genWidth}|${def.genDepth}"

    /** The plan for this definition, generated and validated on first ask. Null when it never managed one. */
    fun get(def: BuildingDef): Building? {
        val k = key(def)
        cache[k]?.let { return it }
        return try {
            val b = generateBuilding(options(def))
            cache[k] = b
            summaries[def.id] = Summary(def.id, def.name, b.seed, b.floors.size, b.attempts, true, "", b)
            b
        } catch (err: Throwable) {
            Diagnostics.record("procedural", "The building '${def.name}' could not be generated", err)
            summaries[def.id] = Summary(def.id, def.name, seedFor(def), 0, 0, false, err.message ?: "unknown", null)
            null
        }
    }

    /** Ground-floor enclosed footprint in world units, for collision boxes. Null without a plan. */
    fun footprint(def: BuildingDef): Pair<Float, Float>? {
        val b = get(def) ?: return null
        val g = b.floors[0].tiles
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in g.indices) {
            for (x in g[y].indices) {
                if (g[y][x] != Tile.EMPTY) {
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                }
            }
        }
        if (minX > maxX) return null
        return (maxX - minX + 1) * TILE_SIZE to (maxY - minY + 1) * TILE_SIZE
    }
}
