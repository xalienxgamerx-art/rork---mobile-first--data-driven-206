package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.data.BuildingDef
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingOptions
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Rng
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.TILE_SIZE
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.Tile
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.generateBuilding
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.renderAscii
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.validateBuilding
import app.rork.p7buzvrzcw3frddi8y50ou.render.BuildingMesh
import app.rork.p7buzvrzcw3frddi8y50ou.render.MeshBuilder
import app.rork.p7buzvrzcw3frddi8y50ou.render.WorldMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The procedural medieval building generator: determinism, soundness, the architectural
 * variances, a batch run over many seeds, and the hand-authored buildings still standing.
 */
class BuildingGenTest {

    // 1. Seeded RNG determinism, inclusive ranges, shuffling.
    @Test
    fun `seeded rng is deterministic, bounded and inclusive`() {
        val a = Rng(12345L)
        val b = Rng(12345L)
        val seqA = List(100) { a.next() }
        val seqB = List(100) { b.next() }
        assertEquals(seqA, seqB)
        assertTrue(seqA.all { it in 0.0..1.0 })

        val r = Rng(99L)
        repeat(500) {
            val v = r.int(3, 7)
            assertTrue("int($v) out of 3..7", v in 3..7)
        }
        // Inclusive range: both endpoints come up across seeds.
        val spread = (1L..200L).map { Rng(it).int(0, 1) }.toSet()
        assertEquals(setOf(0, 1), spread)

        val items = listOf("a", "b", "c", "d", "e")
        val sh = Rng(7L).shuffle(items)
        assertEquals(items.size, sh.size)
        assertEquals(items.toSet(), sh.toSet())
        assertTrue(Rng(7L).pick(items) in items)

        assertNotEquals(Rng(1L).next(), Rng(2L).next())
    }

    // 2. Same seed and options produce identical buildings.
    @Test
    fun `same seed and options produce identical buildings`() {
        val a = generateBuilding(BuildingOptions(seed = 4242L, floors = 3, width = 21, depth = 13))
        val b = generateBuilding(BuildingOptions(seed = 4242L, floors = 3, width = 21, depth = 13))
        assertEquals(a, b)
        assertEquals(a.floors.flatMap { it.rooms }, b.floors.flatMap { it.rooms })
        val c = generateBuilding(BuildingOptions(seed = 4243L, floors = 3, width = 21, depth = 13))
        assertNotEquals(a, c)
    }

    // 3. Generated buildings contain at least three floors.
    @Test
    fun `buildings have at least three floors`() {
        for (s in 0L..19L) {
            assertTrue(generateBuilding(BuildingOptions(seed = s)).floors.size >= 3)
        }
        assertEquals(5, generateBuilding(BuildingOptions(seed = 1L, floors = 5)).floors.size)
        // Fewer than three is clamped up, never down.
        assertEquals(3, generateBuilding(BuildingOptions(seed = 2L, floors = 1)).floors.size)
    }

    // 4. Staircases align between floors.
    @Test
    fun `staircases align on every floor`() {
        for (s in 0L..29L) {
            val b = generateBuilding(BuildingOptions(seed = s))
            val keys = b.floors.map { f ->
                f.tiles.flatMapIndexed { y, row ->
                    row.mapIndexed { x, t -> if (t == Tile.STAIRS) "$x,$y" else null }.filterNotNull()
                }.sorted().joinToString("|")
            }
            assertTrue(keys[0].isNotEmpty())
            assertTrue(keys.all { it == keys[0] })
        }
    }

    // 5 & 6. Every generated building passes validation (which includes per-floor reachability).
    @Test
    fun `generated buildings pass validation`() {
        for (s in 0L..99L) {
            val b = generateBuilding(BuildingOptions(seed = s))
            val issues = validateBuilding(b)
            assertTrue("seed $s failed validation: $issues", issues.isEmpty())
        }
    }

    /** Independent flood fill, so reachability is not only the generator grading its own work. */
    private fun unreachableCount(b: app.rork.p7buzvrzcw3frddi8y50ou.procgen.Building): Int {
        var worst = 0
        for (f in b.floors) {
            val g = f.tiles
            var walkable = 0
            var start: Pair<Int, Int>? = null
            for (y in g.indices) for (x in g[y].indices) {
                if (g[y][x] in intArrayOf(Tile.FLOOR, Tile.DOOR, Tile.STAIRS, Tile.BALCONY)) {
                    walkable++
                    if (start == null && g[y][x] == Tile.STAIRS) start = x to y
                }
            }
            val from = start ?: continue
            val seen = HashSet<Long>()
            val queue = ArrayDeque<Pair<Int, Int>>()
            seen.add(from.first.toLong() * 1_000_000 + from.second)
            queue.addLast(from)
            while (queue.isNotEmpty()) {
                val (cx, cy) = queue.removeLast()
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (ny !in g.indices || nx !in g[ny].indices) continue
                    if (g[ny][nx] !in intArrayOf(Tile.FLOOR, Tile.DOOR, Tile.STAIRS, Tile.BALCONY)) continue
                    if (seen.add(nx.toLong() * 1_000_000 + ny)) queue.addLast(nx to ny)
                }
            }
            worst = maxOf(worst, walkable - seen.size)
        }
        return worst
    }

    @Test
    fun `every floor has reachable walkable areas`() {
        for (s in 0L..49L) {
            val b = generateBuilding(BuildingOptions(seed = s))
            assertEquals("seed $s has unreachable tiles", 0, unreachableCount(b))
        }
    }

    // 7. Ground floors have exterior entrances.
    @Test
    fun `ground floors have exterior entrances`() {
        for (s in 0L..49L) {
            val g = generateBuilding(BuildingOptions(seed = s)).floors[0].tiles
            val hasDoor = g.any { row -> row.any { it == Tile.DOOR } }
            assertTrue("seed $s has no ground door", hasDoor)
            var opensOutside = false
            for (y in g.indices) for (x in g[y].indices) {
                if (g[y][x] == Tile.DOOR &&
                    listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) ->
                        g.getOrNull(y + dy)?.getOrNull(x + dx) == Tile.EMPTY
                    }
                ) {
                    opensOutside = true
                }
            }
            assertTrue("seed $s door never meets open air", opensOutside)
        }
    }

    // 8. Jetties, balconies, setbacks and loggias generate when applicable.
    @Test
    fun `jetties, balconies, setbacks and loggias generate`() {
        var jettyS = 0
        var jettyN = 0
        var setback = 0
        var balcony = 0
        var loggia = 0
        var railingTiles = 0
        var balconyTiles = 0
        for (s in 0L until 300L) {
            val b = generateBuilding(BuildingOptions(seed = s))
            for (f in b.floors) {
                for (feat in f.features) {
                    when {
                        feat.startsWith("jetty (south") -> jettyS++
                        feat.startsWith("jetty (north") -> jettyN++
                        feat.startsWith("east setback") -> setback++
                        feat.startsWith("recessed balcony") -> loggia++
                        feat.startsWith("balcony") -> balcony++
                    }
                }
                for (row in f.tiles) for (t in row) {
                    if (t == Tile.RAILING) railingTiles++
                    if (t == Tile.BALCONY) balconyTiles++
                }
            }
        }
        assertTrue("no south jetties in 300 seeds", jettyS > 0)
        assertTrue("no north jetties in 300 seeds", jettyN > 0)
        assertTrue("no setbacks in 300 seeds", setback > 0)
        assertTrue("no projecting balconies in 300 seeds", balcony > 0)
        assertTrue("no loggias in 300 seeds", loggia > 0)
        assertTrue("no railing tiles were laid", railingTiles > 0)
        assertTrue("no balcony tiles were laid", balconyTiles > 0)
    }

    // 9. A batch run across many seeds all generate successfully.
    @Test
    fun `batch generation over 500 seeds succeeds and stays sound`() {
        var totalAttempts = 0
        for (s in 0L until 500L) {
            val b = generateBuilding(BuildingOptions(seed = s))
            assertEquals(s, b.seed)
            assertTrue("seed $s failed validation", validateBuilding(b).isEmpty())
            totalAttempts += b.attempts
        }
        // Soundness is enforced by retries; if retries were doing heavy lifting this would
        // be near the 300-attempt cap. It should sit close to one attempt per building.
        assertTrue("too many retries on average: ${totalAttempts / 500.0}", totalAttempts < 2500)
    }

    // 10. Existing hand-authored buildings still function.
    @Test
    fun `hand authored buildings still stand and render`() {
        val content = TestPacks.build(TestPacks.base())
        val cottage = content.buildings["cottage"]
        assertNotNull(cottage)
        assertFalse(cottage!!.procedural)
        val mesh = WorldMesh.get(content, 1)
        assertTrue(mesh.vertexCount > 0)
        assertSame(mesh, WorldMesh.get(content, 1)) // the static batch is cached

        // A pack that adds a procedural building beside the hand-authored one renders both.
        val pack = TestPacks.base(id = "withmanor").copy(
            buildings = TestPacks.base().buildings + BuildingDef(
                id = "manor", name = "Greywarden Hall", type = "hall",
                x = -52f, z = -48f, procedural = true, genFloors = 3,
            ),
        )
        val content2 = TestPacks.build(pack)
        val manor = content2.buildings["manor"]
        assertNotNull(manor)
        val grown = BuildingMesh.emit(MeshBuilder(1 shl 16), manor!!)
        assertTrue(grown)
        val plan = BuildingCache.get(manor)
        assertNotNull(plan)
        assertTrue(validateBuilding(plan!!).isEmpty())
    }

    // Geometry conversion and footprint.
    @Test
    fun `tile plans convert to mesh geometry and ground footprints`() {
        val def = BuildingDef(
            id = "manor", name = "Hall", x = 0f, z = 0f,
            procedural = true, genSeed = 11L, genFloors = 3,
        )
        val m = MeshBuilder(1 shl 16)
        assertTrue(BuildingMesh.emit(m, def))
        assertTrue(m.vertexCount > 0)

        // Deterministic: the same definition grows the same plan, cached.
        assertSame(BuildingCache.get(def), BuildingCache.get(def))
        val footprint = BuildingCache.footprint(def)
        assertNotNull(footprint)
        assertTrue(footprint!!.first >= 17 * TILE_SIZE)
        assertTrue(footprint.second >= 12 * TILE_SIZE)
    }

    // Debug renderer.
    @Test
    fun `ascii renderer shows floors, seed and features`() {
        val b = generateBuilding(BuildingOptions(seed = 7L))
        val text = "seed ${b.seed}\n${renderAscii(b, legend = true)}"
        assertTrue(text.contains("Floor 0"))
        assertTrue(text.contains("Floor 2"))
        assertTrue(text.contains("#")) // walls
        assertTrue(text.contains("=")) // stairs
        assertTrue(text.contains("empty"))
    }
}
