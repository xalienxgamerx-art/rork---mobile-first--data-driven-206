package app.rork.p7buzvrzcw3frddi8y50ou.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.rork.p7buzvrzcw3frddi8y50ou.Diagnostics
import kotlin.concurrent.thread
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Plays the blip voice on the device. One line at a time: a new line cancels the one before
 * it, and closing the conversation stops the sound. The synthesis itself is pure Kotlin
 * ([Vocalizer]); this class only owns the AudioTrack — plain 16-bit streaming, the most
 * widely supported path there is. Everything it does is counted in Diagnostics, so a silent
 * phone can be told apart from an untriggered one.
 */
object VoicePlayer {
    @Volatile var enabled: Boolean = true

    private val synth = Vocalizer()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "blip-voice") }
    private val current = AtomicReference<AudioTrack?>(null)

    /** Smallest buffer the device will stream without stutter, in bytes. */
    private val minBuffer: Int by lazy {
        try {
            AudioTrack.getMinBufferSize(Vocalizer.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(2048)
        } catch (_: Exception) {
            4096
        }
    }

    /** Speaks for a person: their folk's voice, shaped by their character. */
    fun speakFor(age: Int, raceVoice: String, personality: Map<String, Float>, id: String, text: String) {
        if (!enabled) return
        val choice = Vocalizer.chooseVoice(age, raceVoice, personality, id)
        speak(text, choice.voice, choice.pitch, choice.rate)
    }

    /** A line the player asked to hear — the Settings sample — sounds even with voices off. */
    fun sample() {
        speak(
            "Well met, traveller. The vale is quiet today.",
            pitch = 0,
            force = true,
        )
    }

    fun speak(
        text: String,
        voice: Vocalizer.VoiceName = Vocalizer.VoiceName.HERO,
        pitch: Int = 0,
        rate: Double = 1.0,
        force: Boolean = false,
    ) {
        if (!enabled && !force) return
        executor.execute {
            stop()
            try {
                val rendered = synth.renderSpeak(text, voice = voice, pitch = pitch, rate = rate)
                // 16-bit PCM: every device and emulator speaks it.
                val pcm = ShortArray(rendered.samples.size) { i ->
                    (rendered.samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Vocalizer.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(pcm.size * 2, minBuffer))
                    .build()
                track.play()
                current.set(track)
                // The player accepts what it can, not always everything at once: a write may
                // take part of the buffer and leave the rest for later. Every part is written
                // until the line is out, with bounded patience when the buffer stays full and
                // a reported error rather than silence when it refuses outright.
                var offset = 0
                var stalled = 0
                var refused = 0
                while (offset < pcm.size) {
                    val n = track.write(pcm, offset, pcm.size - offset)
                    if (n < 0) {
                        refused = n
                        break
                    }
                    if (n == 0) {
                        if (++stalled > 500) break // ~5 s of a full buffer: give up, report below
                        Thread.sleep(10)
                        continue
                    }
                    stalled = 0
                    offset += n
                }
                if (refused < 0 || offset < pcm.size) {
                    Diagnostics.updateVoice { it.copy(errors = it.errors + 1) }
                    Diagnostics.record(
                        "voice",
                        "A spoken line was only partly written (${offset} of ${pcm.size} samples" +
                            if (refused < 0) ", the player returned $refused)." else ", the buffer stayed full).",
                    )
                } else {
                    Diagnostics.updateVoice { it.copy(linesVoiced = it.linesVoiced + 1) }
                }
                // Released on its own thread when the line ends, or by [stop] on dismissal.
                // The voice thread itself must never wait for a line that has finished:
                // a queued sleep here delayed every reply in a fast conversation.
                thread(isDaemon = true, name = "blip-voice-release") {
                    try {
                        Thread.sleep((rendered.seconds * 1000).toLong() + 150)
                        if (current.compareAndSet(track, null)) track.release()
                    } catch (_: InterruptedException) {
                    }
                }
            } catch (e: Exception) {
                Diagnostics.updateVoice { it.copy(errors = it.errors + 1) }
                // Voice is a delight, never a dependency: a failed blip stays silent, but the
                // reason is reported rather than swallowed.
                Diagnostics.record("voice", "A spoken line could not be played.", e)
            }
        }
    }

    /** Stops and frees whatever is sounding right now. */
    fun stop() {
        current.getAndSet(null)?.let {
            try {
                it.pause()
                it.flush()
                it.release()
            } catch (e: Exception) {
                Diagnostics.record("voice", "A finished line could not be stopped cleanly.", e)
            }
        }
    }
}
