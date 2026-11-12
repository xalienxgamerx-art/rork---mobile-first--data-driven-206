package app.rork.p7buzvrzcw3frddi8y50ou.render

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.data.hexToRgb
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.DOOR_H
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.FloorPlan
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.STORY
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.TILE_SIZE
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Tile
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Terrain
import kotlin.math.atan2

/**
 * Turns a generated tile plan into 3D geometry, one box at a time, appended to the static world
 * mesh by the same [MeshBuilder] the hand-authored buildings use. Each floor stacks [STORY]
 * world units above the last, tile (x, y) directly above tile (x, y); the plan's south facade
 * (its front doors) looks toward the village square like every building's door does, because
 * larger tile rows map to local -z.
 *
 * Initial representation per tile: floor — a walkable slab; wall — a solid box; door — jambs,
 * a header and an ajar leaf; window — sill, header and a glowing pane; beam — a timber post;
 * stairs — solid stepped blocks rising within the floor height; balcony — an open-air plank
 * slab; railing — a balustrade with a top rail, run along whichever way its neighbours run.
 * Empty tiles make nothing.
 */
object BuildingMesh {
    private const val WINDOW_SILL = 0.9f
    private const val WINDOW_TOP = 1.9f
    private val TIMBER = floatArrayOf(0.30f, 0.21f, 0.14f)
    private val SOLID = intArrayOf(
        Tile.WALL, Tile.FLOOR, Tile.BEAM, Tile.STAIRS, Tile.DOOR, Tile.WINDOW,
    )

    /** Emits the generated building for [def]. False when no plan could be grown for it. */
    fun emit(m: MeshBuilder, def: BuildingDef): Boolean {
        val gen = BuildingCache.get(def) ?: return false
        val y = Terrain.height(def.x, def.z)
        val yaw = atan2(def.x, def.z)
        m.place(def.x, y - 0.1f, def.z, yaw)
        val wall = hexToRgb(def.wall)
        val roof = hexToRgb(def.roof)
        for (f in gen.floors) emitFloor(m, gen, f, wall)
        emitRoof(m, gen, roof)
        return true
    }

    private fun emitFloor(m: MeshBuilder, gen: Building, f: FloorPlan, wall: FloatArray) {
        val base = f.level * STORY
        val cx = (gen.width - 1) / 2f
        val cz = (gen.height - 1) / 2f
        val stone = f.material == "stone"
        val slab = if (stone) floatArrayOf(0.50f, 0.48f, 0.44f) else floatArrayOf(0.42f, 0.31f, 0.20f)
        val leaf = floatArrayOf(0.38f, 0.27f, 0.16f)
        val tiles = f.tiles

        for (ty in tiles.indices) {
            for (tx in tiles[ty].indices) {
                val x = (tx - cx) * TILE_SIZE
                val z = (cz - ty) * TILE_SIZE
                when (tiles[ty][tx]) {
                    Tile.FLOOR -> m.box(x, base + 0.06f, z, TILE_SIZE / 2f, 0.06f, TILE_SIZE / 2f, slab[0], slab[1], slab[2])

                    Tile.WALL -> m.box(x, base + STORY / 2f, z, TILE_SIZE / 2f, STORY / 2f, TILE_SIZE / 2f, wall[0] * 0.96f, wall[1] * 0.96f, wall[2] * 0.96f)

                    Tile.DOOR -> {
                        // Header above the opening, jambs at the sides, a leaf ajar.
                        m.box(x, base + (STORY + DOOR_H) / 2f, z, TILE_SIZE / 2f, (STORY - DOOR_H) / 2f, 0.12f, wall[0] * 0.9f, wall[1] * 0.9f, wall[2] * 0.9f)
                        m.box(x - TILE_SIZE / 2f + 0.07f, base + DOOR_H / 2f, z, 0.07f, DOOR_H / 2f, 0.12f, TIMBER[0], TIMBER[1], TIMBER[2])
                        m.box(x + TILE_SIZE / 2f - 0.07f, base + DOOR_H / 2f, z, 0.07f, DOOR_H / 2f, 0.12f, TIMBER[0], TIMBER[1], TIMBER[2])
                        m.box(x - TILE_SIZE * 0.2f, base + (DOOR_H - 0.08f) / 2f, z, TILE_SIZE * 0.28f, (DOOR_H - 0.08f) / 2f, 0.05f, leaf[0], leaf[1], leaf[2])
                    }

                    Tile.WINDOW -> {
                        m.box(x, base + WINDOW_SILL / 2f, z, TILE_SIZE / 2f, WINDOW_SILL / 2f, TILE_SIZE / 2f, wall[0] * 0.96f, wall[1] * 0.96f, wall[2] * 0.96f)
                        m.box(x, base + (STORY + WINDOW_TOP) / 2f, z, TILE_SIZE / 2f, (STORY - WINDOW_TOP) / 2f, TILE_SIZE / 2f, wall[0] * 0.96f, wall[1] * 0.96f, wall[2] * 0.96f)
                        m.glow = true
                        m.box(x, base + (WINDOW_SILL + WINDOW_TOP) / 2f, z, TILE_SIZE / 2f + 0.03f, (WINDOW_TOP - WINDOW_SILL) / 2f, TILE_SIZE / 2f + 0.03f, 1.0f, 0.78f, 0.38f)
                        m.glow = false
                    }

                    Tile.BEAM -> m.box(x, base + STORY / 2f, z, 0.09f, STORY / 2f, 0.09f, TIMBER[0], TIMBER[1], TIMBER[2])

                    Tile.STAIRS -> {
                        // Solid stepped blocks rising within the floor height, along +x of the stair block.
                        val col = tx - gen.stairs.x
                        val h = STORY * (col + 1) / 3f
                        m.box(x, base + h / 2f, z, TILE_SIZE / 2f, h / 2f, TILE_SIZE / 2f, slab[0] * 0.9f, slab[1] * 0.9f, slab[2] * 0.9f)
                    }

                    Tile.BALCONY -> m.box(x, base + 0.06f, z, TILE_SIZE / 2f, 0.06f, TILE_SIZE / 2f, 0.36f, 0.27f, 0.17f)

                    Tile.RAILING -> {
                        val alongX = (tx > 0 && tiles[ty][tx - 1] == Tile.RAILING) ||
                            (tx < tiles[ty].size - 1 && tiles[ty][tx + 1] == Tile.RAILING)
                        val ex = if (alongX) TILE_SIZE / 2f else 0.07f
                        val ez = if (alongX) 0.07f else TILE_SIZE / 2f
                        m.box(x, base + 0.5f, z, ex, 0.5f, ez, TIMBER[0], TIMBER[1], TIMBER[2])
                        m.box(x, base + 1.04f, z, ex, 0.05f, ez, 0.42f, 0.31f, 0.19f)
                    }
                }
            }
        }
    }

    /** A gable roof over the topmost enclosed part, leaving terraces and balconies open to the sky. */
    private fun emitRoof(m: MeshBuilder, gen: Building, roof: FloatArray) {
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

        m.around(centreX, y0 + rise * 0.2f, centreZ)
        m.quad(centreX - ex, y0, centreZ - ez, centreX + ex, y0, centreZ - ez, centreX + ex, y0 + rise, centreZ, centreX - ex, y0 + rise, centreZ, roof[0], roof[1], roof[2])
        m.quad(centreX - ex, y0, centreZ + ez, centreX + ex, y0, centreZ + ez, centreX + ex, y0 + rise, centreZ, centreX - ex, y0 + rise, centreZ, roof[0] * 0.9f, roof[1] * 0.9f, roof[2] * 0.9f)
        m.tri(centreX - ex, y0, centreZ - ez, centreX - ex, y0, centreZ + ez, centreX - ex, y0 + rise, centreZ, roof[0] * 0.8f, roof[1] * 0.8f, roof[2] * 0.8f)
        m.tri(centreX + ex, y0, centreZ - ez, centreX + ex, y0, centreZ + ez, centreX + ex, y0 + rise, centreZ, roof[0] * 0.8f, roof[1] * 0.8f, roof[2] * 0.8f)
    }
}
