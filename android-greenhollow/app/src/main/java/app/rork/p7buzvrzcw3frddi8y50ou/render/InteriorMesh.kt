package app.rork.p7buzvrzcw3frddi8y50ou.render

import app.rork.p7buzvrzcw3frddi8y50ou.procgen.DOOR_H
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.FloorPlan
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.STORY
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.TILE_SIZE
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Tile
import app.rork.p7buzvrzcw3frddi8y50ou.sim.ActiveInterior

/**
 * Turns the active interior instance's tile plan into geometry in the interior's OWN local
 * space — the overworld never sees a vertex of it, and the plan's cached exterior mesh stays
 * exactly as it is. Read from the inside, the same generator that built the shell now dresses
 * the rooms: plank floors underfoot, plaster walls, doorways left open (jambs and a header,
 * no leaf — you see the way out), glowing windows, posts, stairs, and per-floor ceilings that
 * leave the stairwell open. The single-slot cache keys on the instance itself, so entering a
 * different building replaces the mesh instead of accumulating them.
 */
object InteriorMesh {
    private const val WINDOW_SILL = 0.9f
    private const val WINDOW_TOP = 1.9f
    private val TIMBER = floatArrayOf(0.30f, 0.21f, 0.14f)
    private val PLASTER = floatArrayOf(0.80f, 0.74f, 0.62f)
    private val PLANK = floatArrayOf(0.47f, 0.36f, 0.23f)
    private val CEILING = floatArrayOf(0.58f, 0.53f, 0.43f)
    private val SOLID = intArrayOf(Tile.WALL, Tile.FLOOR, Tile.BEAM, Tile.STAIRS, Tile.DOOR, Tile.WINDOW)

    /** The mesh for one interior instance; [key] is compared by identity, not equals. */
    class Entry(val key: ActiveInterior, val mesh: MeshBuilder) {
        val vertexCount get() = mesh.vertexCount
    }

    private var cache: Entry? = null

    /** The mesh for the active interior, rebuilt only when a different instance is active. */
    @Synchronized
    fun get(inst: ActiveInterior?): Entry? {
        if (inst == null) {
            cache = null
            return null
        }
        cache?.let { if (it.key === inst) return it }
        val m = MeshBuilder(1 shl 18)
        emit(m, inst)
        return Entry(inst, m).also { cache = it }
    }

    private fun emit(m: MeshBuilder, inst: ActiveInterior) {
        val gen = inst.plan
        // The builder's transform stays at the origin: every coordinate below is already
        // interior-local, which is the whole point of the instance's own space.
        m.place(0f, 0f, 0f, 0f)
        val ex = gen.width * TILE_SIZE / 2f + 0.4f
        val ez = gen.height * TILE_SIZE / 2f + 0.4f
        // Dark ground under the house, so no gap ever shows the sky through the floor.
        m.box(0f, -0.18f, 0f, ex, 0.18f, ez, 0.28f, 0.26f, 0.23f)
        val top = gen.floors.maxOf { it.level }
        for (f in gen.floors) {
            emitFloor(m, gen, f)
            if (f.level < top) emitCeiling(m, gen, f)
        }
        emitRoof(m, gen)
    }

    private fun emitFloor(m: MeshBuilder, gen: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building, f: FloorPlan) {
        val base = f.level * STORY
        val cx = (gen.width - 1) / 2f
        val cz = (gen.height - 1) / 2f
        val tiles = f.tiles
        for (ty in tiles.indices) {
            for (tx in tiles[ty].indices) {
                val x = (tx - cx) * TILE_SIZE
                val z = (cz - ty) * TILE_SIZE
                when (tiles[ty][tx]) {
                    Tile.FLOOR -> m.box(x, base + 0.06f, z, TILE_SIZE / 2f, 0.06f, TILE_SIZE / 2f, PLANK[0], PLANK[1], PLANK[2])

                    Tile.WALL -> m.box(x, base + STORY / 2f, z, TILE_SIZE / 2f, STORY / 2f, TILE_SIZE / 2f, PLASTER[0], PLASTER[1], PLASTER[2])

                    Tile.DOOR -> {
                        // An open doorway: header and jambs, no leaf — the way out stays visible.
                        m.box(x, base + (STORY + DOOR_H) / 2f, z, TILE_SIZE / 2f, (STORY - DOOR_H) / 2f, 0.12f, PLASTER[0] * 0.94f, PLASTER[1] * 0.94f, PLASTER[2] * 0.94f)
                        m.box(x - TILE_SIZE / 2f + 0.07f, base + DOOR_H / 2f, z, 0.07f, DOOR_H / 2f, 0.12f, TIMBER[0], TIMBER[1], TIMBER[2])
                        m.box(x + TILE_SIZE / 2f - 0.07f, base + DOOR_H / 2f, z, 0.07f, DOOR_H / 2f, 0.12f, TIMBER[0], TIMBER[1], TIMBER[2])
                        m.box(x, base + 0.03f, z, TILE_SIZE / 2f, 0.03f, TILE_SIZE / 2f, 0.38f, 0.28f, 0.18f)
                    }

                    Tile.WINDOW -> {
                        m.box(x, base + WINDOW_SILL / 2f, z, TILE_SIZE / 2f, WINDOW_SILL / 2f, TILE_SIZE / 2f, PLASTER[0], PLASTER[1], PLASTER[2])
                        m.box(x, base + (STORY + WINDOW_TOP) / 2f, z, TILE_SIZE / 2f, (STORY - WINDOW_TOP) / 2f, TILE_SIZE / 2f, PLASTER[0], PLASTER[1], PLASTER[2])
                        m.glow = true
                        m.box(x, base + (WINDOW_SILL + WINDOW_TOP) / 2f, z, TILE_SIZE / 2f + 0.03f, (WINDOW_TOP - WINDOW_SILL) / 2f, TILE_SIZE / 2f + 0.03f, 1.0f, 0.78f, 0.38f)
                        m.glow = false
                    }

                    Tile.BEAM -> m.box(x, base + STORY / 2f, z, 0.09f, STORY / 2f, 0.09f, TIMBER[0], TIMBER[1], TIMBER[2])

                    Tile.STAIRS -> {
                        val col = tx - gen.stairs.x
                        val h = STORY * (col + 1) / 3f
                        m.box(x, base + h / 2f, z, TILE_SIZE / 2f, h / 2f, TILE_SIZE / 2f, PLANK[0] * 0.88f, PLANK[1] * 0.88f, PLANK[2] * 0.88f)
                    }

                    Tile.BALCONY -> m.box(x, base + 0.06f, z, TILE_SIZE / 2f, 0.06f, TILE_SIZE / 2f, PLANK[0] * 0.8f, PLANK[1] * 0.8f, PLANK[2] * 0.8f)

                    Tile.RAILING -> {
                        val alongX = (tx > 0 && tiles[ty][tx - 1] == Tile.RAILING) ||
                            (tx < tiles[ty].size - 1 && tiles[ty][tx + 1] == Tile.RAILING)
                        val ex2 = if (alongX) TILE_SIZE / 2f else 0.07f
                        val ez2 = if (alongX) 0.07f else TILE_SIZE / 2f
                        m.box(x, base + 0.5f, z, ex2, 0.5f, ez2, TIMBER[0], TIMBER[1], TIMBER[2])
                        m.box(x, base + 1.04f, z, ex2, 0.05f, ez2, 0.42f, 0.31f, 0.19f)
                    }
                }
            }
        }
    }

    /** A plaster ceiling under the next floor, everywhere but the stairwell and open air. */
    private fun emitCeiling(m: MeshBuilder, gen: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building, f: FloorPlan) {
        val above = gen.floors.firstOrNull { it.level == f.level + 1 } ?: return
        val base = f.level * STORY + STORY
        val cx = (gen.width - 1) / 2f
        val cz = (gen.height - 1) / 2f
        for (ty in f.tiles.indices) {
            for (tx in f.tiles[ty].indices) {
                val t = f.tiles[ty][tx]
                if (t == Tile.EMPTY || t == Tile.STAIRS) continue
                val up = above.tiles.getOrNull(ty)?.getOrNull(tx) ?: Tile.EMPTY
                // Above an empty tile is open air (a setback or a projection); above stairs is
                // the stairwell. Both stay open to the floor above.
                if (up == Tile.EMPTY || up == Tile.STAIRS) continue
                val x = (tx - cx) * TILE_SIZE
                val z = (cz - ty) * TILE_SIZE
                m.box(x, base - 0.05f, z, TILE_SIZE / 2f, 0.05f, TILE_SIZE / 2f, CEILING[0], CEILING[1], CEILING[2])
            }
        }
    }

    /** The underside of the roof over the topmost enclosed part; terraces stay open. */
    private fun emitRoof(m: MeshBuilder, gen: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building) {
        val top = gen.floors.last()
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (y in top.tiles.indices) {
            for (x in top.tiles[y].indices) {
                if (top.tiles[y][x] in SOLID) {
                    minX = minOf(minX, x); maxX = maxOf(maxX, x)
                    minY = minOf(minY, y); maxY = maxOf(maxY, y)
                }
            }
        }
        if (minX > maxX) return

        val cx = (gen.width - 1) / 2f
        val cz = (gen.height - 1) / 2f
        val centreX = ((minX + maxX) / 2f - cx) * TILE_SIZE
        val centreZ = (cz - (minY + maxY) / 2f) * TILE_SIZE
        val ex = (maxX - minX + 1) / 2f * TILE_SIZE + 0.5f
        val ez = (maxY - minY + 1) / 2f * TILE_SIZE + 0.6f
        val y0 = top.level * STORY + STORY
        val rise = (maxY - minY + 1) * TILE_SIZE * 0.42f
        val r = 0.34f; val g = 0.24f; val b = 0.16f

        m.around(centreX, y0 + rise * 0.2f, centreZ)
        m.quad(centreX - ex, y0, centreZ - ez, centreX + ex, y0, centreZ - ez, centreX + ex, y0 + rise, centreZ, centreX - ex, y0 + rise, centreZ, r, g, b)
        m.quad(centreX - ex, y0, centreZ + ez, centreX + ex, y0, centreZ + ez, centreX + ex, y0 + rise, centreZ, centreX - ex, y0 + rise, centreZ, r * 0.9f, g * 0.9f, b * 0.9f)
        m.tri(centreX - ex, y0, centreZ - ez, centreX - ex, y0, centreZ + ez, centreX - ex, y0 + rise, centreZ, r * 0.8f, g * 0.8f, b * 0.8f)
        m.tri(centreX + ex, y0, centreZ - ez, centreX + ex, y0, centreZ + ez, centreX + ex, y0 + rise, centreZ, r * 0.8f, g * 0.8f, b * 0.8f)
    }
}
