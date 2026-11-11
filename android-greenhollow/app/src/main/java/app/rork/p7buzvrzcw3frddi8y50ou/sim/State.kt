package app.rork.p7buzvrzcw3frddi8y50ou.sim

import kotlinx.serialization.Serializable

/** Everything in this file is persistent instance state. It references definitions only by id. */
const val SAVE_VERSION = 4

/** The player takes part in the social world under this id, like any villager. */
const val PLAYER_ID = "player"

/**
 * What one character thinks of another, in eight independent measures from 0 to 100. The record
 * is directional: "hawk>wren" is Hawk's opinion of Wren and says nothing about hers of him, so
 * asymmetry needs no duplicate bookkeeping. The dimensions are deliberately independent: one can
 * respect a person they resent, or trust someone they do not particularly like.
 */
@Serializable
data class RelationshipState(
    /** Whose opinion this is. */
    var holder: String,
    /** The person the opinion is about. */
    var other: String,
    /** How well they know one another; grows with every real encounter. */
    var familiarity: Float = 0f,
    var friendship: Float = 0f,
    var trust: Float = 0f,
    var respect: Float = 0f,
    var affection: Float = 0f,
    var fear: Float = 0f,
    var resentment: Float = 0f,
    var hostility: Float = 0f,
) {
    /** Repairs a hand-edited or damaged record without losing what can be kept. */
    fun sanitize() {
        familiarity = fin(familiarity)
        friendship = fin(friendship)
        trust = fin(trust)
        respect = fin(respect)
        affection = fin(affection)
        fear = fin(fear)
        resentment = fin(resentment)
        hostility = fin(hostility)
    }

    private fun fin(v: Float): Float = if (v.isNaN() || v.isInfinite()) 0f else v.coerceIn(0f, 100f)

    /** A forgiving reading of the whole record, for one-word standings. */
    fun warmth(): Float = friendship * 0.4f + affection * 0.3f + trust * 0.2f + respect * 0.1f -
        hostility * 0.6f - resentment * 0.3f
}

/** A record of something that happened, kept by the character who experienced or heard of it. */
@Serializable
data class MemoryEntry(
    val id: String,
    /** The kind of event: rescue, betrayal, shared_work, ... One of [SocialEvents]' words or a pack's own. */
    val type: String,
    /** World minute the event happened (not when the memory was formed). */
    val minute: Double,
    /** How emotionally weighty the event was, 0..100; at [PROTECTED_SIGNIFICANCE] or above it never fades. */
    val significance: Float,
    /** 1 for happy events, -1 for grim ones. */
    val sentiment: Int,
    /** How sure the keeper is that this happened as recorded, 0..1. Falls as gossip travels. */
    var confidence: Float = 1f,
    /** True when the keeper saw it themselves; false when they only heard tell. */
    val witnessed: Boolean = true,
    /** Everyone else involved, player included. */
    val others: List<String> = emptyList(),
    val place: String = "",
    val text: String = "",
) {
    fun sanitize() {
        if (confidence.isNaN() || confidence.isInfinite()) confidence = 1f
        confidence = confidence.coerceIn(0f, 1f)
    }

    companion object {
        /** At this weight or above, a memory fades slowly and is never pruned. */
        const val PROTECTED_SIGNIFICANCE = 70f
        /** Above this, a memory may survive as history long after its feeling has gone. */
        const val HISTORICAL_SIGNIFICANCE = 55f
    }
}

/** What one holder knows of a piece of gossip, and how the telling has worn it. */
@Serializable
data class GossipKnowledge(
    val minute: Double,
    var confidence: Float = 1f,
    /** 0 = the story as it happened; 1 = bent as far as the world allows. */
    var distortion: Float = 0f,
    /** Who they heard it from. */
    val via: String = "",
) {
    fun sanitize() {
        if (confidence.isNaN() || confidence.isInfinite()) confidence = 1f
        if (distortion.isNaN() || distortion.isInfinite()) distortion = 0f
        confidence = confidence.coerceIn(0f, 1f)
        distortion = distortion.coerceIn(0f, 1f)
    }
}

/** A story travelling the vale: one event, told holder to holder, fraying a little each time. */
@Serializable
data class GossipRecord(
    val id: String,
    /** The memory that started it, kept identifiable however the telling distorts. */
    val eventRef: String,
    /** A [SocialEvents] word describing the underlying kind of event. */
    val category: String,
    /** Who the story is about, player included. */
    var subject: String,
    var source: String,
    val originMinute: Double,
    var text: String = "",
    var importance: Float = 30f,
    /** Who holds the story: a person's id or [PLAYER_ID] -> what they know of it. */
    val holders: MutableMap<String, GossipKnowledge> = mutableMapOf(),
) {
    fun sanitize() {
        importance = if (importance.isNaN() || importance.isInfinite()) 30f else importance.coerceIn(0f, 100f)
        for (k in holders.values) k.sanitize()
    }
}

/** Everything the vale says about itself: who thinks what of whom, and what stories travel. */
@Serializable
data class SocialSave(
    /** Keyed "holder>other"; the map form keeps every opinion unique by construction. */
    val relationships: MutableMap<String, RelationshipState> = mutableMapOf(),
    val gossip: MutableList<GossipRecord> = mutableListOf(),
    /** Pair "a|b" (ids in sorted order) -> world minute the pair last truly interacted. */
    val cooldowns: MutableMap<String, Double> = mutableMapOf(),
)

@Serializable
data class BladeGeometry(
    /** cm */
    val length: Float = 82f,
    /** cm */
    val width: Float = 4.5f,
    /** mm */
    val thickness: Float = 6f,
    /** degrees of arc along the blade */
    val curvature: Float = 2f,
    /** 0 = no distal taper, 1 = needle point */
    val taper: Float = 0.5f,
    /** cm */
    val handle: Float = 18f,
    /** grams */
    val pommel: Float = 180f,
    /** degrees, inclusive edge angle */
    val edgeAngle: Float = 30f,
    val fuller: Boolean = false,
)

@Serializable
data class WeaponStats(
    val mass: Float = 1f,
    val balance: Float = 10f,
    val inertia: Float = 0.1f,
    val reach: Float = 1.6f,
    val handling: Float = 50f,
    val cutting: Float = 50f,
    val thrust: Float = 50f,
    val integrity: Float = 50f,
    val edgeRetention: Float = 50f,
    val damage: Float = 10f,
    val speed: Float = 1f,
)

@Serializable
data class ForgedWeapon(
    val name: String,
    val materialId: String,
    val geometry: BladeGeometry,
    val stats: WeaponStats,
    val quality: String,
)

@Serializable
data class ItemInstance(
    val uid: String,
    val defId: String,
    var count: Int = 1,
    var condition: Float = 1f,
    val forged: ForgedWeapon? = null,
)

/** How the player's character looks. Colours are hex strings; an empty skin means "the race's first tone". */
@Serializable
data class Appearance(
    var skin: String = "",
    var hair: String = "#3B291F",
    /** short, long or bald */
    var hairStyle: String = "short",
    var beard: Boolean = false,
    /** 0.9 slight .. 1.1 broad */
    var build: Float = 1f,
    var cloth: String = "#5E6B45",
)

@Serializable
data class PlayerState(
    var name: String = "Wanderer",
    var race: String = "human",
    var background: String = "",
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 122f,
    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var health: Float = 100f,
    var stamina: Float = 100f,
    var magic: Float = 100f,
    var hunger: Float = 70f,
    var fatigue: Float = 80f,
    var comfort: Float = 75f,
    var gold: Int = 20,
    var thirdPerson: Boolean = false,
    val inventory: MutableList<ItemInstance> = mutableListOf(),
    val equipped: MutableMap<String, String> = mutableMapOf(),
    /** Skill id -> rank with its fractional progress to the next rank, e.g. 12.4. */
    val skills: MutableMap<String, Float> = mutableMapOf(),
    /** Attribute id -> base value before equipment. Missing means 5. */
    val attributes: MutableMap<String, Int> = mutableMapOf(),
    var level: Int = 1,
    /** Progress towards the next level, measured in skill ranks gained. */
    var levelProgress: Float = 0f,
    var attributePoints: Int = 0,
    val appearance: Appearance = Appearance(),
)

@Serializable
data class StockEntry(val item: String, var count: Int)

@Serializable
data class NpcState(
    val id: String,
    var x: Float = 0f,
    var z: Float = 0f,
    var hunger: Float = 70f,
    var fatigue: Float = 70f,
    var social: Float = 60f,
    /** How safe this person feels, 100 = at ease. Drops near hostile creatures, recovers at home. */
    var safety: Float = 85f,
    var gold: Int = 30,
    var affinity: Int = 35,
    /** What they are doing now (sleep, eat, work, ...); [activity] is the words for the player. */
    var action: String = "",
    var activity: String = "Idle",
    /** Where the current action leads: "home", "work" or a spot or building id. */
    var actionTarget: String = "",
    var indoors: Boolean = false,
    var lastChat: Double = -1000.0,
    var lastRestockDay: Int = -1,
    /** World minute this person was last simulated; lets far-off lives advance in big safe steps. */
    var lastSim: Double = 0.0,
    /** The journal lines a person keeps: short, readable notes for the player. */
    val memories: MutableList<String> = mutableListOf(),
    /** The structured experiences the social simulation remembers and reasons with. */
    val experiences: MutableList<MemoryEntry> = mutableListOf(),
    val bonds: MutableMap<String, Int> = mutableMapOf(),
    val stock: MutableList<StockEntry> = mutableListOf(),
) {
    /** Repairs needs that a hand-edited or damaged save left impossible, without losing anything else. */
    fun sanitize(minutes: Double) {
        hunger = need(hunger, 70f)
        fatigue = need(fatigue, 70f)
        social = need(social, 60f)
        safety = need(safety, 85f)
        if (lastSim.isNaN() || lastSim > minutes) lastSim = minutes
        if (!action.isBlank() && action !in NpcActions.ALL) action = ""
    }
}

@Serializable
data class CreatureState(
    val uid: String,
    val defId: String,
    var x: Float = 0f,
    var z: Float = 0f,
    var health: Float = 30f,
    var respawnAt: Double = 0.0,
)

@Serializable
data class BestiaryEntry(var firstSeen: Double = 0.0, var kills: Int = 0)

@Serializable
data class RumourEntry(val id: String, val minute: Double = 0.0, val source: String = "")

@Serializable
data class WorldEvent(val minute: Double, val text: String)

@Serializable
data class SaveGame(
    val version: Int = SAVE_VERSION,
    var minutes: Double = 0.0,
    var weather: String = "clear",
    var nextUid: Long = 1,
    val player: PlayerState = PlayerState(),
    val npcs: MutableList<NpcState> = mutableListOf(),
    val creatures: MutableList<CreatureState> = mutableListOf(),
    /** resource node id -> game minute at which it grows back */
    val harvested: MutableMap<String, Double> = mutableMapOf(),
    val history: MutableList<WorldEvent> = mutableListOf(),
    val packs: List<String> = emptyList(),
    /** Facts the world remembers: dialogue choices made, things read, one-off rewards taken. */
    val flags: MutableSet<String> = mutableSetOf(),
    /** Object id -> game minute it was last opened or used. */
    val opened: MutableMap<String, Double> = mutableMapOf(),
    /** Place id -> game minute it was first found. */
    val discovered: MutableMap<String, Double> = mutableMapOf(),
    /** Creature id -> what the player knows of it. */
    val bestiary: MutableMap<String, BestiaryEntry> = mutableMapOf(),
    val rumours: MutableList<RumourEntry> = mutableListOf(),
    /** The social world: opinions, memories of deeds, and the stories travelling between people. */
    val social: SocialSave = SocialSave(),
    /** Seed this world was started with. Kept for reports. */
    val seed: Long = 0,
    /** Current state of the world's random sequence, so a reloaded world continues it. */
    var rngState: Long = 0,
)

/** The kinds of things that happen to people and are remembered. Extensible: a pack or a later
 *  milestone may add its own words, and the simulation treats unknown kinds by their sentiment. */
object SocialEvents {
    const val CONVERSATION = "conversation"
    const val SHARED_WORK = "shared_work"
    const val GOSSIP = "gossip"
    const val ARGUMENT = "argument"
    const val INSULT = "insult"
    const val RECONCILIATION = "reconciliation"
    const val FRIENDSHIP_FORMED = "friendship_formed"
    const val HELP_RECEIVED = "help_received"
    const val HELP_REFUSED = "help_refused"
    const val BETRAYAL = "betrayal"
    const val THEFT = "theft"
    const val COMBAT = "combat"
    const val RESCUE = "rescue"
    const val TRADE_DEAL = "trade_deal"
    const val TRADE_DISPUTE = "trade_dispute"
    const val AFFECTION = "affection"
    const val IMPORTANT_CONVERSATION = "important_conversation"
    const val PUBLIC_HUMILIATION = "public_humiliation"
    const val CRIME_WITNESSED = "crime_witnessed"
    const val PROMISE_BROKEN = "promise_broken"
    const val BRAVERY_WITNESSED = "bravery_witnessed"
    const val LOCAL_EVENT = "local_event"

    /** Words the simulation itself writes; anything else must come from content. */
    val ALL = setOf(
        CONVERSATION, SHARED_WORK, GOSSIP, ARGUMENT, INSULT, RECONCILIATION, FRIENDSHIP_FORMED,
        HELP_RECEIVED, HELP_REFUSED, BETRAYAL, THEFT, COMBAT, RESCUE, TRADE_DEAL, TRADE_DISPUTE,
        AFFECTION, IMPORTANT_CONVERSATION, PUBLIC_HUMILIATION, CRIME_WITNESSED, PROMISE_BROKEN,
        BRAVERY_WITNESSED, LOCAL_EVENT,
    )
}

/** The things a person can be doing. Words a schedule or a decision may use. */
object NpcActions {
    const val SLEEP = "sleep"
    const val EAT = "eat"
    const val WORK = "work"
    const val REST = "rest"
    const val SOCIAL = "social"
    const val WANDER = "wander"
    const val SHOP = "shop"
    const val PATROL = "patrol"
    const val FLEE = "flee"
    val ALL = setOf(SLEEP, EAT, WORK, REST, SOCIAL, WANDER, SHOP, PATROL, FLEE)
}

/**
 * The world's only source of chance. Its state lives in the save, so loading a world and
 * replaying the same inputs gives the same rolls instead of fresh ones. (SplitMix64.)
 */
class WorldRng(private val save: SaveGame) {
    fun nextLong(): Long {
        save.rngState += -0x61c8864680b583ebL
        var z = save.rngState
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1 shl 24).toFloat()
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()
    fun nextInt(bound: Int): Int = if (bound <= 1) 0 else ((nextLong() ushr 33) % bound).toInt()
}

object Calendar {
    private val months = listOf(
        "Dawnmoon", "Thawing", "Seedfall", "Rainhand", "Greentide", "Midsummer",
        "Sunheight", "Goldleaf", "Harvestmoon", "Frostfall", "Duskwane", "Deepwinter",
    )
    private val seasons = listOf(
        "Winter", "Winter", "Spring", "Spring", "Spring", "Summer",
        "Summer", "Summer", "Autumn", "Autumn", "Autumn", "Winter",
    )

    /** A new life begins on the 4th of Frostfall at four in the afternoon. */
    const val START_MINUTES = (9 * 30 + 3) * 1440.0 + 16 * 60.0

    fun hour(minutes: Double): Float = ((minutes % 1440.0) / 60.0).toFloat()
    fun day(minutes: Double): Int = (minutes / 1440.0).toInt()

    fun partOfDay(h: Float): String = when {
        h < 5f -> "Night"
        h < 7f -> "Dawn"
        h < 12f -> "Morning"
        h < 14f -> "Midday"
        h < 17f -> "Afternoon"
        h < 20f -> "Dusk"
        else -> "Night"
    }

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$n$suffix"
    }

    fun dateLabel(minutes: Double): String {
        val d = day(minutes)
        val dayOfYear = ((d % 360) + 360) % 360
        return "${ordinal(dayOfYear % 30 + 1)} of ${months[dayOfYear / 30]}"
    }

    fun season(minutes: Double): String = seasons[(((day(minutes) % 360) + 360) % 360) / 30]

    fun label(minutes: Double): String = "${partOfDay(hour(minutes))}, ${dateLabel(minutes)}"

    fun clock(minutes: Double): String {
        val h = hour(minutes)
        val hh = h.toInt()
        val mm = ((h - hh) * 60).toInt()
        return "%02d:%02d".format(hh, mm)
    }
}
