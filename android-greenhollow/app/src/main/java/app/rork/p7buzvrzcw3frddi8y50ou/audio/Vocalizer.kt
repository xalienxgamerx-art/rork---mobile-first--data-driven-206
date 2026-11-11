package app.rork.p7buzvrzcw3frddi8y50ou.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * Old-school RPG "blip" voice, with singing support. A Kotlin port of the project's own
 * vocalizer design: every letter always maps to the same pitch (on a pentatonic scale, so
 * babble sounds musical), vowels get formant-filtered tones, plosives get a click,
 * fricatives get a hiss, and the rest are short voiced blips.
 *
 * This file is pure synthesis: it renders a line of speech or a melody into PCM samples and
 * knows nothing about Android. [VoicePlayer] owns the device output.
 *
 * Song format: space-separated tokens NOTE[:beats][=lyric] — "C4:1=Ho", "F#3:0.5=la",
 * "Bb2", "R:2" (a rest). A token without a lyric is hummed.
 */
class Vocalizer(private val volume: Float = (0.35f).coerceIn(0f, 1f)) {

    enum class VoiceName { SPRITE, HERO, GIANT, ROBOT }

    /** The character of one voice: bottom pitch, waveform for consonant blips, letters a second. */
    data class Voice(val base: Double, val wave: Wave, val lettersPerSec: Double)

    enum class Wave { SQUARE, TRIANGLE, SAWTOOTH }

    data class SongNote(
        /** e.g. "C4", "F#3", "Bb2", or "R" for a rest. */
        val note: String,
        val beats: Double = 1.0,
        val lyric: String = "",
    )

    /** Rendered audio: mono samples at [Vocalizer.SAMPLE_RATE], plus how long it lasts. */
    class Rendered(val samples: FloatArray, val seconds: Double)

    /** How one person sounds: which voice, their own pitch in semitones, how fast they speak. */
    data class VoiceChoice(val voice: VoiceName, val pitch: Int, val rate: Double)

    private val voices = mapOf(
        VoiceName.SPRITE to Voice(520.0, Wave.SQUARE, 18.0),
        VoiceName.HERO to Voice(330.0, Wave.TRIANGLE, 14.0),
        VoiceName.GIANT to Voice(110.0, Wave.SAWTOOTH, 9.0),
        VoiceName.ROBOT to Voice(260.0, Wave.SQUARE, 16.0),
    )

    // ------------------------------------------------------------ public API

    /** Renders [text] as RPG-style blips. */
    fun renderSpeak(
        text: String,
        voice: VoiceName = VoiceName.HERO,
        pitch: Int = 0,
        rate: Double = 1.0,
        jitter: Double = 0.25,
        rng: java.util.Random = java.util.Random(),
    ): Rendered {
        val v = voices.getValue(voice)
        val step = 1.0 / (v.lettersPerSec * rate)
        val clean = text.lowercase()
        val raiseAt = if (clean.trimEnd().endsWith("?")) lastLetterIndex(clean) else -1

        val buf = Buf()
        var t = LEAD_IN
        for ((i, ch) in clean.withIndex()) {
            when {
                ch in 'a'..'z' -> {
                    val semis = letterSemitone(ch) + pitch + (if (i == raiseAt) 5 else 0) + (rng.nextDouble() * 2 - 1) * jitter
                    val freq = v.base * 2.0.pow(semis / 12.0)
                    val dur = if (isVowel(ch)) step * 1.3 else step
                    letter(ch, freq, t, dur, v, buf, rng)
                    t += step
                }
                ch in '0'..'9' -> {
                    val freq = v.base * 2.0.pow((pitch + PENTATONIC[Character.getNumericValue(ch) % 5] + 12) / 12.0)
                    tone(freq, t, step, v.wave, 0.5, buf)
                    t += step
                }
                ch in ".!?" -> t += step * 6
                ch in ",;:" -> t += step * 3
                ch.isWhitespace() -> t += step * 0.8
            }
        }
        return buf.render(t + TAIL)
    }

    /** Renders a melody from the string format or a list of notes. */
    fun renderSing(
        song: List<SongNote>,
        bpm: Double = 100.0,
        vibrato: Boolean = true,
        voice: VoiceName = VoiceName.HERO,
    ): Rendered {
        val v = voices.getValue(voice)
        val buf = Buf()
        var t = LEAD_IN
        // A song written by hand or by a pack cannot be trusted to sane numbers: tempo and note
        // lengths are clamped, wild frequencies are brought into earshot, nonsense notes are
        // skipped rather than rendered as silence or noise.
        val tempo = if (bpm.isNaN() || bpm.isInfinite()) 100.0 else bpm.coerceIn(MIN_BPM, MAX_BPM)
        for (n in song.take(MAX_NOTES)) {
            val dur = n.beats * 60.0 / tempo
            val freq = noteToFreq(n.note)?.coerceIn(MIN_HZ, MAX_HZ)
            if (freq != null) syllable(n.lyric, freq, t, dur * 0.97, v, vibrato, buf)
            t += dur
        }
        return buf.render(t + TAIL)
    }

    /** Convenience for the string song format. */
    fun renderSing(song: String, bpm: Double = 100.0, vibrato: Boolean = true, voice: VoiceName = VoiceName.HERO): Rendered =
        renderSing(parseSong(song), bpm, vibrato, voice)

    // ------------------------------------------------------------ shared helpers

    private fun letter(ch: Char, freq: Double, t: Double, dur: Double, v: Voice, buf: Buf, rng: java.util.Random) {
        if (isVowel(ch)) {
            vowel(normalizeVowel(ch), freq, t, dur, 0.9, vibrato = false, buf = buf)
        } else {
            consonant(ch, freq, t, dur, v, buf, rng)
        }
    }

    private fun consonant(ch: Char, freq: Double, t: Double, dur: Double, v: Voice, buf: Buf, rng: java.util.Random) {
        when {
            ch in NOISY -> noise(t, minOf(dur, 0.09), if (ch == 'h') 2500.0 else 5000.0, 0.5, buf, rng)
            ch in PLOSIVE -> {
                noise(t, 0.015, 1500.0, 0.6, buf, rng)
                tone(freq * 0.8, t, minOf(dur, 0.05), v.wave, 0.5, buf)
            }
            else -> tone(freq, t, minOf(dur, 0.09), v.wave, 0.5, buf) // l m n r w j v
        }
    }

    private fun tone(freq: Double, t: Double, dur: Double, wave: Wave, peak: Double, buf: Buf) {
        val from = buf.at(t)
        val n = buf.samples(dur)
        var phase = 0.0
        for (i in 0 until n) {
            val s = from + i
            phase += freq / SAMPLE_RATE
            val raw = when (wave) {
                Wave.SQUARE -> if (sin(2 * PI * phase) >= 0) 1.0 else -1.0
                Wave.SAWTOOTH -> 2.0 * (phase - floor(phase)) - 1.0
                Wave.TRIANGLE -> triangleWave(phase)
            }
            buf.mix(s, raw * peak * envelope(i, n))
        }
    }

    /** A vowel: a sawtooth shaped by two band-pass formants, with optional vibrato. */
    private fun vowel(v: Char, freq: Double, t: Double, dur: Double, peak: Double, vibrato: Boolean, buf: Buf) {
        val (f1, f2) = FORMANTS[v] ?: FORMANTS.getValue('a')
        val from = buf.at(t)
        val n = buf.samples(dur)
        var phase = 0.0
        var low1 = 0.0; var band1 = 0.0
        var low2 = 0.0; var band2 = 0.0
        val c1 = chamberlin(f1)
        val c2 = chamberlin(f2)
        for (i in 0 until n) {
            val s = from + i
            val frac = i.toDouble() / n
            val cents = if (vibrato && dur > 0.3) 18.0 * (frac / 0.6).coerceAtMost(1.0) else 0.0
            phase += freq * 2.0.pow(cents / 1200.0) / SAMPLE_RATE
            val saw = 2.0 * (phase - floor(phase)) - 1.0
            low1 += c1.f * band1
            val high1 = saw - low1 - c1.q * band1
            band1 += c1.f * high1
            low2 += c2.f * band2
            val high2 = saw - low2 - c2.q * band2
            band2 += c2.f * high2
            val shaped = band1 * 2.2 + band2 * 0.6 * 2.2
            buf.mix(s, shaped * peak * 0.6 * envelope(i, n, 0.012, 0.04))
        }
    }

    /** A sung syllable: onset consonants, a sustained vowel, coda consonants. */
    private fun syllable(lyric: String, freq: Double, t: Double, dur: Double, v: Voice, vibrato: Boolean, buf: Buf) {
        val letters = lyric.lowercase().filter { it in 'a'..'z' }
        val vi = letters.indexOfFirst { isVowel(it) }
        if (vi == -1) {
            // No vowel: hum the note.
            tone(freq, t, dur, Wave.TRIANGLE, 0.7, buf)
            return
        }
        val onset = letters.take(vi).toList()
        val nucleus = normalizeVowel(letters[vi])
        val coda = letters.drop(vi + 1).filter { !isVowel(it) }.toList()
        val rng = java.util.Random()
        val cLen = minOf(0.05, dur * 0.4 / maxOf(1, onset.size + coda.size))
        var cur = t
        for (c in onset) {
            consonant(c, freq, cur, cLen, v, buf, rng)
            cur += cLen
        }
        val vDur = maxOf(0.05, dur - cLen * (onset.size + coda.size))
        vowel(nucleus, freq, cur, vDur, 1.0, vibrato, buf)
        cur += vDur
        for (c in coda) {
            consonant(c, freq, cur, cLen, v, buf, rng)
            cur += cLen
        }
    }

    private fun noise(t: Double, dur: Double, highpass: Double, peak: Double, buf: Buf, rng: java.util.Random) {
        val from = buf.at(t)
        val n = buf.samples(dur)
        // First-order high-pass; how far above it things pass is set by the corner.
        val k = (1.0 - (2 * PI * highpass / SAMPLE_RATE)).coerceIn(0.0, 0.99)
        var prevIn = 0.0
        var prevOut = 0.0
        for (i in 0 until n) {
            val x = rng.nextDouble() * 2 - 1
            val y = k * (prevOut + x - prevIn)
            prevIn = x
            prevOut = y
            buf.mix(from + i, y * peak * envelope(i, n, 0.002, 0.02))
        }
    }

    /** Linear attack, hold, release — the same shape the design calls an envelope. */
    private fun envelope(i: Int, n: Int, attack: Double = 0.005, release: Double = 0.03): Double {
        val a = maxOf(1, (attack * SAMPLE_RATE).toInt())
        val r = maxOf(1, (release * SAMPLE_RATE).toInt())
        val rise = if (i < a) i.toDouble() / a else 1.0
        val fall = if (i > n - r) ((n - i).toDouble() / r).coerceIn(0.0, 1.0) else 1.0
        return rise * fall
    }

    /** Coefficients for a Chamberlin state-variable band-pass at [freq]. */
    private fun chamberlin(freq: Double): Coefs =
        Coefs(f = 2.0 * sin(PI * freq / SAMPLE_RATE), q = 1.0 / 6.0)

    private class Coefs(val f: Double, val q: Double)

    private fun lastLetterIndex(s: String): Int {
        for (i in s.length - 1 downTo 0) if (s[i] in 'a'..'z') return i
        return -1
    }

    private fun normalizeVowel(ch: Char): Char = if (ch == 'y') 'i' else ch

    private fun isVowel(ch: Char): Boolean = ch in "aeiouy"

    /** A growable mono buffer addressed in seconds. */
    private inner class Buf {
        var data = FloatArray(SAMPLE_RATE / 2)
        var size = 0

        fun at(t: Double): Int = (t * SAMPLE_RATE).toInt()

        fun samples(dur: Double): Int = (dur * SAMPLE_RATE).toInt().coerceAtLeast(1)

        fun ensure(through: Int) {
            if (through < data.size) return
            var cap = data.size
            while (cap <= through) cap *= 2
            data = data.copyOf(cap)
        }

        fun mix(index: Int, value: Double) {
            ensure(index)
            data[index] = (data[index] + value.toFloat()).coerceIn(-1f, 1f)
            if (index + 1 > size) size = index + 1
        }

        fun render(seconds: Double): Rendered {
            val n = maxOf(size, samples(seconds)).coerceAtLeast(1)
            ensure(n)
            val out = FloatArray(n)
            for (i in 0 until n) out[i] = (data[i] * volume).coerceIn(-1f, 1f)
            return Rendered(out, n.toDouble() / SAMPLE_RATE)
        }
    }

    companion object {
        const val SAMPLE_RATE = 22050
        private const val LEAD_IN = 0.05
        private const val TAIL = 0.06

        /** [F1, F2] formant frequencies in Hz. */
        val FORMANTS = mapOf(
            'a' to (800.0 to 1150.0),
            'e' to (530.0 to 1840.0),
            'i' to (270.0 to 2290.0),
            'o' to (570.0 to 840.0),
            'u' to (300.0 to 870.0),
        )

        private val PENTATONIC = intArrayOf(0, 2, 4, 7, 9)
        private const val NOISY = "sfhzxc" // fricatives -> hiss
        private const val PLOSIVE = "bdgkpqt" // plosives -> click
        private val NOTE_INDEX = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

        /** Fixed semitone offset for a letter (a=0 ... z=25), always on the pentatonic scale. */
        fun letterSemitone(ch: Char): Int {
            val idx = ch - 'a'
            return PENTATONIC[idx % 5] + 12 * (idx / 10)
        }

        /**
         * One cycle of a triangle wave, -1 at the cycle's start, peaking at +1 exactly at the
         * half-turn, and the same shape again for any phase a whole turn or more along.
         */
        fun triangleWave(phase: Double): Double = 4.0 * abs(phase - floor(phase + 0.5)) - 1.0

        /** "C#4" -> Hz. Returns null for rests and anything unparsable. */
        fun noteToFreq(note: String): Double? {
            val m = Regex("^([A-Ga-g])([#b]?)(-?\\d)$").find(note.trim()) ?: return null
            val (letter, accidental, octave) = m.destructured
            val semis = (NOTE_INDEX[letter.uppercase().first()] ?: return null) +
                (when (accidental) { "#" -> 1; "b" -> -1; else -> 0 })
            val midi = 12 * (octave.toInt() + 1) + semis
            return 440.0 * 2.0.pow((midi - 69) / 12.0)
        }

        /**
         * Chooses how one person sounds, from their ancestry and their character.
         *
         * Race first: a folk may declare a voice for all its people ("sprite", "hero", "giant",
         * "robot"); otherwise age decides — the young are sprites, the old are giants, and
         * everyone between is a hero. Character then shapes the sound: a bully (aggression 0.7+)
         * grinds like a robot, the fearful (courage 0.2 or less) chirp like a sprite, courage
         * lifts the pitch, aggression weighs it down, and the sociable simply talk faster.
         */
        fun chooseVoice(
            age: Int,
            raceVoice: String = "",
            personality: Map<String, Float> = emptyMap(),
            id: String = "",
        ): VoiceChoice {
            val voice = when (raceVoice.trim().lowercase()) {
                "sprite" -> VoiceName.SPRITE
                "giant" -> VoiceName.GIANT
                "robot" -> VoiceName.ROBOT
                "hero" -> VoiceName.HERO
                else -> when {
                    age in 1..19 -> VoiceName.SPRITE
                    age >= 60 -> VoiceName.GIANT
                    else -> VoiceName.HERO
                }
            }
            val aggression = (personality["aggression"] ?: 0.5f).coerceIn(0f, 1f)
            val courage = (personality["courage"] ?: 0.5f).coerceIn(0f, 1f)
            val sociability = (personality["sociability"] ?: 0.5f).coerceIn(0f, 1f)
            val chosen = when {
                aggression >= 0.7f && voice != VoiceName.GIANT -> VoiceName.ROBOT
                courage <= 0.2f && voice != VoiceName.ROBOT -> VoiceName.SPRITE
                else -> voice
            }
            // A person's own slight pitch, so two farmers never sound identical, bent by
            // temperament: the bold a little higher, the aggressive a little lower. floorMod
            // keeps the note in range even for the ids whose hash comes out negative.
            val own = Math.floorMod(id.hashCode(), 5) - 2
            val temper = ((courage - 0.5f) * 6).toInt() - ((aggression - 0.5f) * 4).toInt()
            val rate = 1.0 + (sociability - 0.5) * 0.4
            return VoiceChoice(chosen, own + temper, rate)
        }

        /**
         * Reads the string song format. A token's note is kept as written (the renderer skips
         * what it cannot pitch), while a broken or wild note length is clamped into a sane one,
         * so a song can never stretch a rendering into hours or collapse it to nothing.
         */
        fun parseSong(song: String): List<SongNote> =
            song.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { tok ->
                val (head, lyric) = tok.split("=", limit = 2).let { if (it.size == 2) it[0] to it[1] else it[0] to "" }
                val parts = head.split(":", limit = 2)
                val note = parts[0]
                val raw = parts.getOrNull(1)?.toDoubleOrNull()
                val beats = if (raw == null || raw.isNaN() || raw.isInfinite()) 1.0 else raw.coerceIn(MIN_BEATS, MAX_BEATS)
                SongNote(note, beats, lyric)
            }

        /** Bounds every rendering is kept within. */
        const val MIN_BPM = 30.0
        const val MAX_BPM = 300.0
        const val MIN_BEATS = 0.25
        const val MAX_BEATS = 16.0
        const val MIN_HZ = 40.0
        const val MAX_HZ = 4000.0
        const val MAX_NOTES = 512
    }
}
