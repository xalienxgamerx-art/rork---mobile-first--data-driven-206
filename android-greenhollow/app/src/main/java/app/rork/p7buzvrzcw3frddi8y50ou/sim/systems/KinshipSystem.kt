package app.rork.p7buzvrzcw3frddi8y50ou.sim.systems

import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import app.rork.p7buzvrzcw3frddi8y50ou.data.KinDef
import app.rork.p7buzvrzcw3frddi8y50ou.sim.Kin
import app.rork.p7buzvrzcw3frddi8y50ou.sim.KinEdge
import app.rork.p7buzvrzcw3frddi8y50ou.sim.PLAYER_ID
import app.rork.p7buzvrzcw3frddi8y50ou.sim.World

/**
 * The family bonds between the vale's people.
 *
 * A bond is stored once, in its canonical direction ("Bram parent of Petra"), and read back
 * through derived reciprocals — nothing is ever written twice. Bonds are pure facts of kinship:
 * they do not say who lives with whom ([HouseholdSystem] keeps that), and they never invent a
 * history — unknown ancestry is simply no record at all, and stays valid.
 *
 * What kinship *does* do is seed the social world: people raised together know each other well
 * before the simulation begins, through the same relationship records every other bond uses,
 * so kinship constrains and flavours but never replaces the social ledger.
 */
class KinshipSystem(private val w: World) {
    private val save get() = w.save
    private val kin get() = save.kin

    /** The words a save may hold that read back as their canonical bond. */
    private val reciprocals: Map<String, String> = Kin.ALL.associate { Kin.reciprocal(it) to it }

    fun edgeKey(from: String, kind: String, to: String) = "$from>$to:$kind"

    // ------------------------------------------------------------ queries

    /** The bond between two people, whichever way round it was written, or null. */
    fun edgeBetween(a: String, b: String): KinEdge? =
        kin.edges.values.firstOrNull { (it.from == a && it.to == b) || (it.from == b && it.to == a) }

    /** True when the two are kin in any recorded way. */
    fun related(a: String, b: String): Boolean = a != b && edgeBetween(a, b) != null

    fun parentsOf(id: String): List<String> = kin.edges.values
        .filter { it.to == id && (it.kind == Kin.PARENT || it.kind == Kin.ADOPTIVE) }
        .map { it.from }

    fun childrenOf(id: String): List<String> = kin.edges.values
        .filter { it.from == id && (it.kind == Kin.PARENT || it.kind == Kin.ADOPTIVE) }
        .map { it.to }

    /** Guardians stand in for parents without being them; wards are the children they keep. */
    fun guardiansOf(id: String): List<String> = kin.edges.values.filter { it.to == id && it.kind == Kin.GUARDIAN }.map { it.from }
    fun wardsOf(id: String): List<String> = kin.edges.values.filter { it.from == id && it.kind == Kin.GUARDIAN }.map { it.to }

    fun spouseOf(id: String): String? = kin.edges.values
        .firstOrNull { it.kind == Kin.SPOUSE && (it.from == id || it.to == id) }
        ?.let { if (it.from == id) it.to else it.from }

    /** Full and half siblings, without distinction here — the edge's kind keeps it. */
    fun siblingsOf(id: String): List<String> = kin.edges.values
        .filter { (it.from == id || it.to == id) && (it.kind == Kin.SIBLING || it.kind == Kin.HALF_SIBLING) }
        .map { if (it.from == id) it.to else it.from }

    fun grandparentsOf(id: String): List<String> = kin.edges.values.filter { it.to == id && it.kind == Kin.GRANDPARENT }.map { it.from }
    fun auntsAndUnclesOf(id: String): List<String> = kin.edges.values.filter { it.to == id && it.kind == Kin.AUNT_UNCLE }.map { it.from }
    fun cousinsOf(id: String): List<String> = kin.edges.values
        .filter { it.kind == Kin.COUSIN && (it.from == id || it.to == id) }
        .map { if (it.from == id) it.to else it.from }

    /**
     * The word [holder] uses for [other]: "child", "parent", "partner" — or null when they are
     * not recorded kin. The bond's own kind reads from the senior end; the holder of the other
     * end reads the reciprocal.
     */
    fun kinLabel(holder: String, other: String): String? {
        val e = edgeBetween(holder, other) ?: return null
        return if (e.from == holder) Kin.reciprocal(e.kind) else e.kind
    }

    /** Every recorded bond of one person, as (other id, the word they use for them). */
    fun bondsOf(id: String): List<Pair<String, String>> = kin.edges.values
        .filter { it.from == id || it.to == id }
        .map { e -> (if (e.from == id) e.to else e.from) to (if (e.from == id) Kin.reciprocal(e.kind) else e.kind) }

    // ------------------------------------------------------------ declaring

    /**
     * Records a bond in canonical form. Symmetric bonds collapse to one record for the sorted
     * pair; a bond already recorded the other way round to the same effect is recognised, not
     * duplicated. Returns true when it was new.
     */
    fun declare(from: String, kind: String, to: String): Boolean {
        if (from == to || kind !in Kin.ALL) return false
        val (a, b, k) = if (kind in Kin.SYMMETRIC && to < from) Triple(to, from, kind) else Triple(from, to, kind)
        return kin.edges.put(edgeKey(a, k, b), KinEdge(a, b, k)) == null
    }

    // ------------------------------------------------------------ binding

    /**
     * Brings the save's bonds in line with the content: pack-declared bonds are instantiated
     * into the save, every stored id is canonicalised with a report instead of a quiet drop,
     * and the social world is seeded so family start out knowing one another. Deterministic;
     * idempotent; safe to run on every load.
     */
    fun bind() {
        // Packs: declared once, in their canonical direction.
        for (d in w.content.kinship) declare(d.from, d.kind, d.to)

        // The save's own records: canonicalise, flip stored reciprocals, drop the broken.
        for (key in kin.edges.keys.toList()) {
            val body = key.substringBeforeLast(':')
            val kindRaw = key.substringAfterLast(':', "")
            val fromRaw = body.substringBefore('>')
            val toRaw = body.substringAfter('>', "")
            val e = kin.edges[key] ?: continue
            val from = e.from.ifBlank { fromRaw }
            val to = e.to.ifBlank { toRaw }
            var kind = e.kind.ifBlank { kindRaw }.trim()
            var a = from
            var b = to
            // A bond stored in the derived word ("Petra child of Bram") is turned the canonical
            // way round instead of being rejected.
            when {
                kind in Kin.ALL -> {}
                reciprocals.containsKey(kind) -> {
                    a = to
                    b = from
                    kind = reciprocals.getValue(kind)
                }
                else -> {
                    Diagnostics.record("save", "A family bond '$key' uses the unknown word '$kind'. It was let go, as no record can be made of it.")
                    kin.edges.remove(key)
                    continue
                }
            }
            if (a != PLAYER_ID) a = w.content.npcs.canonical(a)
            if (b != PLAYER_ID) b = w.content.npcs.canonical(b)
            val known = { id: String -> id == PLAYER_ID || w.content.npcs[id] != null }
            if (a == b || !known(a) || !known(b) || kind !in Kin.ALL) {
                if (!known(a)) Diagnostics.record("save", "A family bond mentions '$a', whom no loaded pack defines. It was let go.")
                if (!known(b)) Diagnostics.record("save", "A family bond mentions '$b', whom no loaded pack defines. It was let go.")
                kin.edges.remove(key)
                continue
            }
            val (ca, cb, ck) = if (kind in Kin.SYMMETRIC && b < a) Triple(b, a, kind) else Triple(a, b, kind)
            val newKey = edgeKey(ca, ck, cb)
            if (newKey != key) {
                kin.edges.remove(key)
                if (!kin.edges.containsKey(newKey)) kin.edges[newKey] = KinEdge(ca, cb, ck)
            } else {
                e.from = ca
                e.to = cb
                e.kind = ck
            }
        }

        seedSocial()
    }

    /**
     * Family start out known to one another: real familiarity, and for the bonds people are
     * raised in, warmth and trust as well. Written directly into the relationship records —
     * family is not news, so this bypasses the chronicle milestones a stranger's first meeting
     * earns.
     */
    private fun seedSocial() {
        val closeKin = setOf(Kin.PARENT, Kin.ADOPTIVE, Kin.GUARDIAN, Kin.SPOUSE, Kin.SIBLING, Kin.HALF_SIBLING)
        val social = w.social
        for (e in kin.edges.values) {
            val pairs = listOf(e.from to e.to, e.to to e.from)
            for ((holder, other) in pairs) {
                if (holder == PLAYER_ID || other == PLAYER_ID) continue
                val r = social.rel(holder, other)
                if (r.familiarity < 55f) r.familiarity = 55f
                if (e.kind in closeKin) {
                    if (r.affection < 45f) r.affection = 45f
                    if (r.trust < 50f) r.trust = 50f
                }
            }
        }
    }
}
