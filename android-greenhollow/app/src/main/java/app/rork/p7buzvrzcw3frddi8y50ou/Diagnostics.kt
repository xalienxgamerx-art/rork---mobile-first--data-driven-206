package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.procgen.BuildingCache
import app.rork.p7buzvrzcw3frddi8y50ou.procgen.renderAscii
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticEntry(val time: Long, val area: String, val message: String)

data class SaveHealth(
    /** Wall-clock time of the last successful save, or 0 when nothing was saved this session. */
    val lastSaved: Long = 0,
    val backups: Int = 0,
    /** Set when the newest save could not be read and an older copy was used. */
    val recovery: String = "",
    /** Set when the loaded save came from an older version of the game. */
    val migration: String = "",
    val lastError: String = "",
)

/** A snapshot of the living-world simulation, published about once a second. */
data class SimHealth(
    val npcs: Int = 0,
    /** How many villagers sit in each tier: near (every frame), regional (2 s), far (abstract). */
    val active: Int = 0,
    val regional: Int = 0,
    val distant: Int = 0,
    val working: Int = 0,
    val sleeping: Int = 0,
    val travelling: Int = 0,
    val fleeing: Int = 0,
    /** Action changes since the world started, and destinations a schedule could not resolve. */
    val transitions: Int = 0,
    val failedDestinations: Int = 0,
    /** Smoothed cost of one simulation pass over everyone. */
    val stepMillis: Float = 0f,
    /** The social world: opinions held, stories travelling, and how busy the meeting of minds is. */
    val relationships: Int = 0,
    val relChanges: Int = 0,
    val memories: Int = 0,
    val memoriesCreated: Int = 0,
    val memoriesPruned: Int = 0,
    val gossip: Int = 0,
    val gossipCreated: Int = 0,
    val gossipShared: Int = 0,
    val gossipRejected: Int = 0,
    val socialAttempts: Int = 0,
    val socialCompleted: Int = 0,
    val socialSkipped: Int = 0,
    val invalidRefs: Int = 0,
    /** Households and their domestic life: roofs kept, work shared, wishes pursued. */
    val households: Int = 0,
    val membershipChanges: Int = 0,
    val dutiesAssigned: Int = 0,
    val goalsActive: Int = 0,
    val goalsCompleted: Int = 0,
)

/** How the dialogue voice is doing this session. */
data class VoiceHealth(
    /** Lines actually handed to the audio player. */
    val linesVoiced: Int = 0,
    val errors: Int = 0,
)

data class DiagnosticsState(
    val entries: List<DiagnosticEntry> = emptyList(),
    val save: SaveHealth = SaveHealth(),
    val sim: SimHealth = SimHealth(),
    val voice: VoiceHealth = VoiceHealth(),
)

/**
 * One place where problems are written down instead of being swallowed. Safe to call from any
 * thread. The most recent entries are kept in memory and in a small log file, so a report still
 * has something to say after a crash and restart.
 */
object Diagnostics {
    private const val MAX = 60
    private const val FILE = "diagnostics.log"
    private val lock = Any()
    private var file: File? = null
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    val state = MutableStateFlow(DiagnosticsState())

    fun attach(dir: File) = synchronized(lock) {
        if (file != null) return@synchronized
        val f = File(dir, FILE)
        file = f
        try {
            if (f.exists()) {
                val old = f.readLines().takeLast(MAX).mapNotNull { line ->
                    val parts = line.split('\t', limit = 3)
                    val time = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                    DiagnosticEntry(time, parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty())
                }
                state.value = state.value.copy(entries = old + state.value.entries)
            }
        } catch (_: Exception) {
            // The log is a convenience. Failing to read it must never stop the game.
        }
    }

    fun record(area: String, message: String, error: Throwable? = null) = synchronized(lock) {
        val text = if (error == null) message else "$message (${error.javaClass.simpleName}: ${error.message?.take(160) ?: "no detail"})"
        val entries = (state.value.entries + DiagnosticEntry(System.currentTimeMillis(), area, text.replace('\n', ' '))).takeLast(MAX)
        state.value = state.value.copy(entries = entries)
        try {
            file?.writeText(entries.joinToString("\n") { "${it.time}\t${it.area}\t${it.message}" })
        } catch (_: Exception) {
        }
    }

    fun updateSave(change: (SaveHealth) -> SaveHealth) = synchronized(lock) {
        state.value = state.value.copy(save = change(state.value.save))
    }

    fun updateSim(change: (SimHealth) -> SimHealth) = synchronized(lock) {
        state.value = state.value.copy(sim = change(state.value.sim))
    }

    fun updateVoice(change: (VoiceHealth) -> VoiceHealth) = synchronized(lock) {
        state.value = state.value.copy(voice = change(state.value.voice))
    }

    fun time(millis: Long): String = if (millis <= 0) "never" else stamp.format(Date(millis))

    /** The plain-text report behind the Share report button. */
    fun report(header: List<String>, contentIssues: List<String>): String = buildString {
        val s = state.value
        appendLine("Greenhollow diagnostics report")
        appendLine("Written ${time(System.currentTimeMillis())}")
        header.forEach { appendLine(it) }
        appendLine()
        appendLine("SAVE")
        appendLine("Last saved: ${time(s.save.lastSaved)}")
        appendLine("Backups available: ${s.save.backups}")
        appendLine("Recovery: ${s.save.recovery.ifEmpty { "none needed" }}")
        appendLine("Upgrade: ${s.save.migration.ifEmpty { "none needed" }}")
        appendLine("Last save error: ${s.save.lastError.ifEmpty { "none" }}")
        appendLine()
        appendLine("WORLD SIMULATION")
        val sim = s.sim
        appendLine("Villagers: ${sim.npcs} (near ${sim.active}, regional ${sim.regional}, far ${sim.distant})")
        appendLine("Right now: ${sim.working} working, ${sim.sleeping} asleep, ${sim.travelling} travelling, ${sim.fleeing} fleeing")
        appendLine("Action changes: ${sim.transitions}; destinations that never resolved: ${sim.failedDestinations}")
        appendLine("Last simulation pass: ${"%.1f".format(sim.stepMillis)} ms")
        appendLine()
        appendLine("SOCIAL WORLD")
        appendLine("Opinions held: ${sim.relationships} (${sim.relChanges} changes since the world began)")
        appendLine("Memories kept: ${sim.memories} (${sim.memoriesCreated} made, ${sim.memoriesPruned} faded out)")
        appendLine("Stories in circulation: ${sim.gossip} (${sim.gossipCreated} begun, ${sim.gossipShared} retellings, ${sim.gossipRejected} declined)")
        appendLine("Encounters: ${sim.socialCompleted} of ${sim.socialAttempts} (${sim.socialSkipped} too soon after the last)")
        if (sim.invalidRefs > 0) appendLine("References that matched no one: ${sim.invalidRefs}")
        appendLine()
        appendLine("HOUSEHOLDS & FAMILIES")
        appendLine("Households: ${sim.households} (${sim.membershipChanges} membership changes, duties shared out ${sim.dutiesAssigned} times)")
        appendLine("Wishes pursued: ${sim.goalsActive} active, ${sim.goalsCompleted} achieved")
        appendLine()
        appendLine("PROCEDURAL BUILDINGS")
        if (BuildingCache.summaries.isEmpty()) {
            appendLine("None.")
        } else {
            for (s in BuildingCache.summaries.values) {
                appendLine("${s.id} (\"${s.name}\"): seed ${s.seed}, ${s.floors} floors, ${s.attempts} attempt(s), ${if (s.sound) "sound" else "FAILED: ${s.problems}"}")
                s.building?.let { appendLine(renderAscii(it, legend = true)) }
            }
        }
        appendLine()
        appendLine("VOICE")
        appendLine("Lines voiced this session: ${s.voice.linesVoiced} (${s.voice.errors} failed)")
        if (s.voice.linesVoiced == 0 && s.voice.errors == 0) appendLine("No line has been voiced yet — no one has spoken.")
        appendLine()
        appendLine("CONTENT PACKS")
        if (contentIssues.isEmpty()) appendLine("No problems.") else contentIssues.forEach { appendLine(it) }
        appendLine()
        appendLine("RECENT ERRORS")
        if (s.entries.isEmpty()) appendLine("None recorded.") else s.entries.asReversed().forEach {
            appendLine("${time(it.time)} [${it.area}] ${it.message}")
        }
    }
}
