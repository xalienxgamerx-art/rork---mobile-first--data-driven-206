package app.rork.p7buzvrzcw3frddi8y50ou

import app.rork.p7buzvrzcw3frddi8y50ou.audio.Vocalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blip-voice synthesizer: letter-to-pitch mapping, song parsing, and the rendered PCM.
 * Pure JVM — the device player ([app.rork.p7buzvrzcw3frddi8y50ou.audio.VoicePlayer]) is thin.
 */
class VocalizerTest {

    private val v = Vocalizer()

    @Test
    fun `a letter always sings the same pentatonic note`() {
        val a = Vocalizer.letterSemitone('a')
        assertEquals(0, a)
        assertEquals(a, Vocalizer.letterSemitone('a'))
        // Octave wrap every ten letters, so the scale never leaves the mode.
        assertEquals(Vocalizer.letterSemitone('a') + 12, Vocalizer.letterSemitone('k'))
        assertEquals(Vocalizer.letterSemitone('a') + 24, Vocalizer.letterSemitone('u'))
        // All letters land on the pentatonic grid {0,2,4,7,9} mod 12.
        for (ch in 'a'..'z') {
            val s = Vocalizer.letterSemitone(ch)
            assertTrue("letter $ch off the grid", s % 12 in intArrayOf(0, 2, 4, 7, 9))
        }
    }

    @Test
    fun `notes convert to their real frequencies`() {
        assertEquals(440.0, Vocalizer.noteToFreq("A4")!!, 0.01)
        assertEquals(261.63, Vocalizer.noteToFreq("C4")!!, 0.5)
        // One sharp is one semitone up.
        assertTrue(Vocalizer.noteToFreq("F#4")!! > Vocalizer.noteToFreq("F4")!!)
        assertEquals(Vocalizer.noteToFreq("Gb4")!!, Vocalizer.noteToFreq("F#4")!!, 0.01)
        assertNull(Vocalizer.noteToFreq("R"))
        assertNull(Vocalizer.noteToFreq("nope"))
    }

    @Test
    fun `song strings parse into notes`() {
        val song = Vocalizer.parseSong("C4:2=Hail R:1 G3:0.5=la Bb2")
        assertEquals(4, song.size)
        assertEquals("C4", song[0].note)
        assertEquals(2.0, song[0].beats, 0.0)
        assertEquals("Hail", song[0].lyric)
        assertEquals("R", song[1].note)
        assertEquals(1.0, song[1].beats, 0.0)
        assertEquals(0.5, song[2].beats, 0.0)
        assertEquals("Bb2", song[3].note)
        assertEquals("", song[3].lyric)
        assertTrue(Vocalizer.parseSong("").isEmpty())
    }

    @Test
    fun `spoken lines render non-silent audio of a plausible length`() {
        val rendered = v.renderSpeak("Hello, villager! How are you?", Vocalizer.VoiceName.HERO)
        assertTrue(rendered.seconds > 0.5 && rendered.seconds < 5.0)
        assertEquals((rendered.seconds * Vocalizer.SAMPLE_RATE).toInt() - 1, rendered.samples.size - 1)
        val peak = rendered.samples.maxOf { kotlin.math.abs(it) }
        assertTrue("line is silent", peak > 0.05f)
        assertTrue("line clips hard", peak <= 1.0f)
        // The lead-in and the tail stay quiet, so lines never click against each other.
        assertTrue(rendered.samples.take(Vocalizer.SAMPLE_RATE / 50).all { it == 0f })
    }

    @Test
    fun `a question ends higher than the same line without one`() {
        val flat = v.renderSpeak("Do you need something", Vocalizer.VoiceName.HERO, rng = java.util.Random(7))
        val raised = v.renderSpeak("Do you need something?", Vocalizer.VoiceName.HERO, rng = java.util.Random(7))
        // The raised line is at least as long (six extra steps for the question mark).
        assertTrue(raised.seconds >= flat.seconds)
    }

    @Test
    fun `songs render with rests and hums without a vowel`() {
        val rendered = v.renderSing("C4:1=Hail R:0.5 G3:1 Bb2:0.5", bpm = 120.0)
        // 3 sung beats at 120 bpm = 1.5 s, plus lead-in and tail.
        assertTrue(rendered.seconds in 1.5..2.5)
        assertTrue(rendered.samples.any { it != 0f })
        val hum = v.renderSing("A4:1", bpm = 100.0)
        assertTrue(hum.samples.any { it != 0f })
    }

    @Test
    fun `every voice renders and the giant is the slowest`() {
        val text = "Welcome to Greenhollow, traveller. The bread is fresh today."
        for (voice in Vocalizer.VoiceName.entries) {
            val r = v.renderSpeak(text, voice)
            assertTrue("$voice silent", r.samples.maxOf { kotlin.math.abs(it) } > 0.05f)
        }
        val giant = v.renderSpeak(text, Vocalizer.VoiceName.GIANT).seconds
        val sprite = v.renderSpeak(text, Vocalizer.VoiceName.SPRITE).seconds
        assertTrue(giant > sprite)
    }

    @Test
    fun `race declares the voice for all its people, whatever their age`() {
        assertEquals(
            Vocalizer.VoiceName.GIANT,
            Vocalizer.chooseVoice(age = 70, raceVoice = "giant").voice,
        )
        assertEquals(
            Vocalizer.VoiceName.SPRITE,
            Vocalizer.chooseVoice(age = 70, raceVoice = "Sprite").voice,
        )
        // An unknown or empty declaration falls back to sounding by age.
        assertEquals(
            Vocalizer.VoiceName.GIANT,
            Vocalizer.chooseVoice(age = 70, raceVoice = "squeaky").voice,
        )
        assertEquals(
            Vocalizer.VoiceName.HERO,
            Vocalizer.chooseVoice(age = 30, raceVoice = "").voice,
        )
    }

    @Test
    fun `the triangle wave peaks at the half-turn and returns home`() {
        assertEquals(-1.0, Vocalizer.triangleWave(0.0), 1e-9)
        assertEquals(0.0, Vocalizer.triangleWave(0.25), 1e-9)
        assertEquals(1.0, Vocalizer.triangleWave(0.5), 1e-9)
        assertEquals(0.0, Vocalizer.triangleWave(0.75), 1e-9)
        assertEquals(-1.0, Vocalizer.triangleWave(1.0), 1e-9)
        // A whole turn or more along, the shape begins again exactly.
        assertEquals(Vocalizer.triangleWave(0.25), Vocalizer.triangleWave(1.25), 1e-9)
        assertEquals(Vocalizer.triangleWave(0.5), Vocalizer.triangleWave(2.5), 1e-9)
        for (i in 0..200) {
            val x = i / 50.0
            assertTrue("wave leaves its bounds at x=$x", Vocalizer.triangleWave(x) in -1.0..1.0)
        }
    }

    @Test
    fun `a person's own pitch stays in range whatever the sign of their id`() {
        val middle = mapOf("aggression" to 0.5f, "courage" to 0.5f, "sociability" to 0.5f)
        val ids = List(200) { "npc-variant-$it" } + listOf("", "pop_farmer_1", "a very long identifier for a humble shepherd")
        for (id in ids) {
            val choice = Vocalizer.chooseVoice(30, personality = middle, id = id)
            assertTrue("pitch out of range for '$id'", choice.pitch in -7..7)
            assertEquals("the same id sounds the same twice", choice, Vocalizer.chooseVoice(30, personality = middle, id = id))
        }
    }

    @Test
    fun `a broken song is clamped into something singable, never nonsense`() {
        assertEquals(1.0, Vocalizer.parseSong("C4:banana").single().beats, 0.0)
        assertEquals(1.0, Vocalizer.parseSong("C4:NaN").single().beats, 0.0)
        assertEquals(Vocalizer.MIN_BEATS, Vocalizer.parseSong("C4:0").single().beats, 0.0)
        assertEquals(Vocalizer.MAX_BEATS, Vocalizer.parseSong("C4:9999").single().beats, 0.0)
        // A wild tempo lands within the bounds; an absurd one cannot stretch a line into hours.
        val fast = v.renderSing("C4:1=la D4:1=la", bpm = 300.0).seconds
        assertEquals(fast, v.renderSing("C4:1=la D4:1=la", bpm = 100_000.0).seconds, 1e-6)
        assertEquals(v.renderSing("C4:1", bpm = 100.0).seconds, v.renderSing("C4:1", bpm = Double.NaN).seconds, 1e-6)
        // A preposterously long song is trimmed, and every sample stays in range.
        val long = v.renderSing(List(2000) { Vocalizer.SongNote("C4") }, bpm = 60.0)
        assertTrue(long.samples.all { it in -1f..1f })
        // An empty song renders a short silence rather than a crash.
        assertTrue(v.renderSing("").samples.isNotEmpty())
    }

    @Test
    fun `character shapes the sound`() {
        val middle = mapOf("aggression" to 0.5f, "courage" to 0.5f, "sociability" to 0.5f)
        // A bully grinds like a robot; a giant stays a giant.
        val bully = Vocalizer.chooseVoice(30, personality = middle + ("aggression" to 0.9f))
        assertEquals(Vocalizer.VoiceName.ROBOT, bully.voice)
        val oldBully = Vocalizer.chooseVoice(70, personality = middle + ("aggression" to 0.9f))
        assertEquals(Vocalizer.VoiceName.GIANT, oldBully.voice)
        // The fearful chirp; the robot stays a robot.
        val timid = Vocalizer.chooseVoice(30, personality = middle + ("courage" to 0.1f))
        assertEquals(Vocalizer.VoiceName.SPRITE, timid.voice)
        val timidBully = Vocalizer.chooseVoice(30, personality = mapOf("aggression" to 0.9f, "courage" to 0.1f))
        assertEquals(Vocalizer.VoiceName.ROBOT, timidBully.voice)
        // The bold sing higher than the meek, everything else equal.
        val bold = Vocalizer.chooseVoice(30, personality = middle + ("courage" to 1f), id = "same")
        val meek = Vocalizer.chooseVoice(30, personality = middle + ("courage" to 0f), id = "same")
        assertTrue(bold.pitch > meek.pitch)
        // Aggression weighs the voice down below an even temper.
        val even = Vocalizer.chooseVoice(30, personality = middle, id = "same")
        val angry = Vocalizer.chooseVoice(30, personality = middle + ("aggression" to 1f), id = "same")
        assertTrue(angry.pitch < even.pitch)
        // The sociable talk faster; the withdrawn slower.
        val chatty = Vocalizer.chooseVoice(30, personality = middle + ("sociability" to 1f))
        val withdrawn = Vocalizer.chooseVoice(30, personality = middle + ("sociability" to 0f))
        assertTrue(chatty.rate > 1.0)
        assertTrue(withdrawn.rate < 1.0)
        // The same id and character always sound the same.
        assertEquals(
            Vocalizer.chooseVoice(30, personality = middle, id = "pop_farmer_3"),
            Vocalizer.chooseVoice(30, personality = middle, id = "pop_farmer_3"),
        )
    }
}
