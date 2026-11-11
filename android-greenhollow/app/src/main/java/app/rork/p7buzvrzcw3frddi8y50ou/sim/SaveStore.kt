package app.rork.p7buzvrzcw3frddi8y50ou.sim

import android.content.Context
import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class Settings(
    /** 0 low, 1 medium, 2 high */
    val graphics: Int = 1,
    val adaptive: Boolean = true,
    val showFps: Boolean = false,
    val leftHanded: Boolean = false,
    val sensitivity: Float = 1f,
    /** RPG-style blip voice for dialogue lines. */
    val voices: Boolean = true,
    val disabledPacks: Set<String> = emptySet(),
)

/** A decoded save plus what had to be done to read it. */
class Decoded(val save: SaveGame, val fromVersion: Int)

/** What loading found. [notice] is a sentence for the player when something unusual happened.
 *  [newerVersion] is set when the world on disk was written by a newer version of the game:
 *  loading stops there and the decision of what happens to it belongs to the player. */
class LoadResult(val save: SaveGame?, val notice: String = "", val newerVersion: Int = 0)

class NewerSaveException(val version: Int) : Exception("Saved by a newer version of the game (save format $version).")

/**
 * Step-by-step upgrades of old saves. They work on raw JSON, before the save is decoded, so a
 * field can be renamed or reshaped without the current data classes having to understand the
 * old layout. Pure: no files, so each step can be unit tested with a JSON string.
 */
object SaveMigrations {
    private val steps: Map<Int, (JsonObject) -> JsonObject> = mapOf(1 to ::v1ToV2, 2 to ::v2ToV3, 3 to ::v3ToV4)

    fun versionOf(root: JsonObject): Int = root["version"]?.jsonPrimitive?.intOrNull ?: 1

    fun migrate(input: JsonObject): JsonObject {
        var root = input
        var version = versionOf(root)
        if (version > SAVE_VERSION) throw NewerSaveException(version)
        while (version < SAVE_VERSION) {
            val step = steps[version] ?: throw IllegalStateException("No upgrade path from save format $version.")
            root = step(root)
            version++
            root = JsonObject(root + ("version" to JsonPrimitive(version)))
        }
        return root
    }

    private fun renameKeys(obj: JsonObject?, names: Map<String, String>): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        obj?.forEach { (k, v) -> out[names[k] ?: k] = v }
        return JsonObject(out)
    }

    /**
     * Version 2 adds attributes, levels, nine equipment slots, appearance, discoveries and a
     * stored random seed. An old character gets average attributes and the same four free
     * points a new one starts with; their sword and shield move to the new hand slots.
     */
    private fun v1ToV2(root: JsonObject): JsonObject {
        val player = root["player"] as? JsonObject ?: JsonObject(emptyMap())
        val newPlayer = LinkedHashMap<String, JsonElement>(player)
        newPlayer["equipped"] = renameKeys(player["equipped"] as? JsonObject, mapOf("weapon" to "main_hand", "shield" to "off_hand"))
        newPlayer["skills"] = renameKeys(player["skills"] as? JsonObject, mapOf("blade" to "blades", "block" to "blocking"))
        newPlayer["attributes"] = JsonObject(Progression.ATTRIBUTES.associateWith { JsonPrimitive(Progression.BASE_ATTRIBUTE) })
        newPlayer["attributePoints"] = JsonPrimitive(Progression.STARTING_POINTS)
        newPlayer["level"] = JsonPrimitive(1)
        val name = player["name"]?.jsonPrimitive?.content.orEmpty()
        val uid = root["nextUid"]?.jsonPrimitive?.longOrNull ?: 1L
        val seed = name.hashCode().toLong() * 2654435761L + uid * 40503L + 0x5EEDL
        val out = LinkedHashMap<String, JsonElement>(root)
        out["player"] = JsonObject(newPlayer)
        out["flags"] = JsonArray(listOf(JsonPrimitive(FLAG_MIGRATED_V1)))
        out["seed"] = JsonPrimitive(seed)
        out["rngState"] = JsonPrimitive(seed)
        return JsonObject(out)
    }

    /** Tells the engine to fill in discoveries quietly for a world that predates them. */
    const val FLAG_MIGRATED_V1 = "sys:migrated_v1"

    /**
     * Version 3 gives every person needs for safety, a current activity target and the world
     * minute they were last simulated. Older saves get calm needs, and their last-simulation
     * mark is set to now so no one tries to live through the years already past.
     */
    private fun v2ToV3(root: JsonObject): JsonObject {
        val now = root["minutes"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val npcs = root["npcs"] as? JsonArray ?: return root
        val updated = npcs.map { entry ->
            val obj = entry as? JsonObject ?: return@map entry
            JsonObject(obj + (
                "lastSim" to JsonPrimitive(now)
                ))
        }
        return JsonObject(LinkedHashMap(root).apply { put("npcs", JsonArray(updated)) })
    }

    /**
     * Version 4 adds the social world: directional relationships, structured memories and
     * travelling gossip. Nothing is thrown away — the old single-number bonds become the
     * friendship and familiarity of real relationship records, and how each person felt about
     * the player seeds their opinion of them, so old friendships and grudges keep their meaning.
     */
    private fun v3ToV4(root: JsonObject): JsonObject {
        val npcs = root["npcs"] as? JsonArray ?: return root
        val relationships = LinkedHashMap<String, JsonElement>()
        fun put(holder: String, other: String, dims: Map<String, Float>) {
            if (holder.isEmpty() || other.isEmpty()) return
            val obj = LinkedHashMap<String, JsonElement>()
            obj["holder"] = JsonPrimitive(holder)
            obj["other"] = JsonPrimitive(other)
            for ((k, v) in dims) obj[k] = JsonPrimitive(v)
            relationships["$holder>$other"] = JsonObject(obj)
        }
        val updated = npcs.map { entry ->
            val obj = entry as? JsonObject ?: return@map entry
            val id = obj["id"]?.jsonPrimitive?.content ?: return@map entry
            // Bonds: an old warmth score around 0 grows into friendship and familiarity.
            val bonds = obj["bonds"] as? JsonObject
            bonds?.forEach { (other, v) ->
                val b = v.jsonPrimitive.doubleOrNull?.toFloat() ?: 0f
                put(
                    id, other,
                    mapOf(
                        "familiarity" to (40f + abs(b) * 4f).coerceIn(0f, 90f),
                        "friendship" to (50f + b * 5f).coerceIn(5f, 95f),
                        "trust" to (45f + b * 3f).coerceIn(5f, 95f),
                    ),
                )
            }
            // Affinity: how they felt about the player becomes a full opinion.
            val a = obj["affinity"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 35f
            put(
                id, PLAYER_ID,
                mapOf(
                    "familiarity" to (a * 0.8f).coerceIn(0f, 80f),
                    "friendship" to (a * 0.9f).coerceIn(0f, 100f),
                    "trust" to (a * 0.6f).coerceIn(0f, 100f),
                    "affection" to (a * 0.5f).coerceIn(0f, 100f),
                ),
            )
            // Structured memories: old saves had none, the field simply appears empty.
            JsonObject(LinkedHashMap(obj).apply { put("experiences", JsonArray(emptyList())) })
        }
        val social = LinkedHashMap<String, JsonElement>()
        social["relationships"] = JsonObject(relationships)
        social["gossip"] = JsonArray(emptyList())
        social["cooldowns"] = JsonObject(emptyMap())
        return JsonObject(LinkedHashMap(root).apply { put("npcs", JsonArray(updated)); put("social", JsonObject(social)) })
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v
}

/** Versioned, crash-safe persistence for the living world and for player preferences.
 *
 *  The file operations work on a plain [File] directory so they can be exercised by unit
 *  tests; the [Context] overloads point them at the app's own storage. */
object SaveStore {
    const val FILE = "world.json"
    private const val BACKUPS = 3
    private const val PREFS = "greenhollow"

    /** Where a save too new for this game goes when the player chooses an older one. Never deleted. */
    const val NEWER_ARCHIVE = "world.newer.bak.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(save: SaveGame): String = json.encodeToString(SaveGame.serializer(), save)

    fun decodeDetailed(text: String): Decoded {
        val root = json.parseToJsonElement(text).jsonObject
        val from = SaveMigrations.versionOf(root)
        val save = json.decodeFromJsonElement(SaveGame.serializer(), SaveMigrations.migrate(root))
        return Decoded(save, from)
    }

    fun decode(text: String): SaveGame = decodeDetailed(text).save

    fun file(context: Context): File = File(context.filesDir, FILE)

    private fun backup(dir: File, i: Int) = File(dir, "world.bak$i.json")

    private fun countBackups(dir: File) = (1..BACKUPS).count { backup(dir, it).exists() }

    private fun versionOf(file: File): Int = try {
        SaveMigrations.versionOf(json.parseToJsonElement(file.readText()).jsonObject)
    } catch (_: Exception) {
        0
    }

    private fun isNewer(file: File) = versionOf(file) > SAVE_VERSION

    /** Moves a save too new for this game aside under a fresh name; archives are never overwritten. */
    private fun archive(dir: File, file: File) {
        var n = 0
        var target = File(dir, NEWER_ARCHIVE)
        while (target.exists()) {
            n++
            target = File(dir, "world.newer.bak$n.json")
        }
        if (!file.renameTo(target)) {
            Diagnostics.record("save", "${file.name} could not be moved aside and was left where it is.")
        }
    }

    fun load(context: Context): LoadResult = load(context.filesDir)

    /**
     * Loads the newest save that can be read. An unreadable file is set aside as "*.corrupt"
     * (never deleted) and the next older copy is tried. A save written by a newer version of
     * the game stops the search right there: it is the player's newest world, never fallen
     * past behind their back — an older backup is used only when they say so.
     */
    fun load(dir: File): LoadResult {
        val candidates = listOf(File(dir, FILE)) + (1..BACKUPS).map { backup(dir, it) }
        var failed = 0
        for ((index, file) in candidates.withIndex()) {
            if (!file.exists()) continue
            try {
                val decoded = decodeDetailed(file.readText())
                val recovery = if (failed == 0) "" else
                    "The newest save could not be read, so an earlier copy from ${Diagnostics.time(file.lastModified())} was loaded. The damaged file was kept."
                val migration = if (decoded.fromVersion == SAVE_VERSION) "" else
                    "This world was upgraded from save format ${decoded.fromVersion} to $SAVE_VERSION."
                if (recovery.isNotEmpty()) Diagnostics.record("save", "Recovered from backup ${if (index == 0) "" else index}. $recovery")
                if (migration.isNotEmpty()) Diagnostics.record("save", migration)
                Diagnostics.updateSave { it.copy(backups = countBackups(dir), recovery = recovery, migration = migration) }
                return LoadResult(decoded.save, recovery)
            } catch (e: NewerSaveException) {
                Diagnostics.record("save", "${file.name} was written by a newer version of the game (save format ${e.version}) and was left untouched.", e)
                val notice = "Your world was saved by a newer version of the game (save format ${e.version}) and cannot be opened by this one. It was left untouched."
                Diagnostics.updateSave { it.copy(backups = countBackups(dir), recovery = notice) }
                return LoadResult(null, notice, newerVersion = e.version)
            } catch (e: Exception) {
                failed++
                Diagnostics.record("save", "${file.name} could not be read and was set aside.", e)
                file.renameTo(File(dir, file.name.removeSuffix(".json") + ".corrupt.json"))
            }
        }
        val notice = if (failed > 0) "Your saved world and its backups could not be read. The files were kept, and the details are in Diagnostics." else ""
        Diagnostics.updateSave { it.copy(backups = countBackups(dir), recovery = notice) }
        return LoadResult(null, notice)
    }

    fun write(context: Context, text: String) = write(context.filesDir, text)

    /**
     * Writes the world so that a crash at any moment leaves a complete save behind: the text
     * goes to a temporary file and is flushed to storage, the previous saves shift down one
     * backup slot, and only then does the new file take the live name in a single rename.
     * A live save written by a newer version of the game is never pushed into the backups:
     * writing refuses instead, so no automatic save can quietly bury it.
     */
    @Synchronized
    fun write(dir: File, text: String) {
        val target = File(dir, FILE)
        if (target.exists() && isNewer(target)) {
            throw IOException("The world on disk was written by a newer version of the game (save format ${versionOf(target)}). It was left untouched.")
        }
        val tmp = File(dir, "$FILE.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (target.exists()) {
            for (i in BACKUPS - 1 downTo 1) {
                val from = backup(dir, i)
                if (from.exists()) from.renameTo(backup(dir, i + 1))
            }
            target.renameTo(backup(dir, 1))
        }
        if (!tmp.renameTo(target)) {
            // Put the previous save back rather than leave the world without a live file.
            val previous = backup(dir, 1)
            if (!target.exists() && previous.exists()) previous.renameTo(target)
            tmp.delete()
            throw IOException("The new save could not be moved into place.")
        }
        Diagnostics.updateSave { it.copy(lastSaved = System.currentTimeMillis(), backups = countBackups(dir), lastError = "") }
    }

    /**
     * Starting a new life clears the old world and its backups. Saves too new for this game
     * are archived, never deleted; corrupt files set aside are left alone.
     */
    fun delete(context: Context) = delete(context.filesDir)

    fun delete(dir: File) {
        val live = File(dir, FILE)
        if (live.exists()) {
            if (isNewer(live)) archive(dir, live) else live.delete()
        }
        for (i in 1..BACKUPS) {
            val f = backup(dir, i)
            if (!f.exists()) continue
            if (isNewer(f)) archive(dir, f) else f.delete()
        }
        Diagnostics.updateSave { it.copy(backups = 0, recovery = "", migration = "") }
    }

    fun restoreOlderBackup(context: Context): LoadResult = restoreOlderBackup(context.filesDir)

    /**
     * The player's explicit choice to give up the newer world: its files are moved aside (kept
     * forever as world.newer.bak*.json) and the newest save this game can read is loaded.
     */
    fun restoreOlderBackup(dir: File): LoadResult {
        val live = File(dir, FILE)
        if (live.exists() && isNewer(live)) archive(dir, live)
        for (i in 1..BACKUPS) {
            val f = backup(dir, i)
            if (f.exists() && isNewer(f)) archive(dir, f)
        }
        // Put the newest old save back under the live name so the world is whole again.
        for (i in 1..BACKUPS) {
            val f = backup(dir, i)
            if (f.exists() && !isNewer(f)) {
                if (!f.renameTo(live)) Diagnostics.record("save", "${f.name} could not be restored as the live save and was left where it is.")
                break
            }
        }
        Diagnostics.record("save", "An older save was restored at the player's request. The newer world was kept as $NEWER_ARCHIVE and can be continued after updating the game.")
        return load(dir)
    }

    /**
     * Moves saves too new for this game aside so that ordinary writing can resume. Called
     * only on the player's explicit fresh start; anything of the current format is untouched.
     */
    fun archiveNewerWorld(context: Context) = archiveNewerWorld(context.filesDir)

    fun archiveNewerWorld(dir: File) {
        val live = File(dir, FILE)
        if (live.exists() && isNewer(live)) archive(dir, live)
        for (i in 1..BACKUPS) {
            val f = backup(dir, i)
            if (f.exists() && isNewer(f)) archive(dir, f)
        }
    }

    fun loadSettings(context: Context): Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(
            graphics = p.getInt("graphics", 1).coerceIn(0, 2),
            adaptive = p.getBoolean("adaptive", true),
            showFps = p.getBoolean("showFps", false),
            leftHanded = p.getBoolean("leftHanded", false),
            sensitivity = p.getFloat("sensitivity", 1f).coerceIn(0.4f, 2f),
            voices = p.getBoolean("voices", true),
            disabledPacks = p.getStringSet("disabledPacks", emptySet())?.toSet() ?: emptySet(),
        )
    }

    fun saveSettings(context: Context, s: Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("graphics", s.graphics)
            .putBoolean("adaptive", s.adaptive)
            .putBoolean("showFps", s.showFps)
            .putBoolean("leftHanded", s.leftHanded)
            .putFloat("sensitivity", s.sensitivity)
            .putBoolean("voices", s.voices)
            .putStringSet("disabledPacks", s.disabledPacks)
            .apply()
    }
}
