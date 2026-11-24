package dev.vantara.replicatorops.ui.lcars

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Short synthesised computer chirps (generated in code, no sound files). */
object Chirp {
    @Volatile
    var enabled = true

    private const val RATE = 22050
    private val exec = Executors.newSingleThreadExecutor()

    private data class Tone(val hz: Double, val ms: Int)

    fun tap() = play(Tone(1660.0, 40))
    fun nav() = play(Tone(1240.0, 32), Tone(1860.0, 42))
    fun confirm() = play(Tone(880.0, 55), Tone(1320.0, 75))
    fun deny() = play(Tone(520.0, 90), Tone(390.0, 120))
    fun alert() = play(Tone(1000.0, 110), Tone(0.0, 40), Tone(1000.0, 110))
    /** "Computer is answering" — two quick rising tones before a spoken reply. */
    fun computer() = play(Tone(1480.0, 55), Tone(0.0, 20), Tone(1975.0, 85))

    private fun play(vararg tones: Tone) {
        if (!enabled) return
        exec.execute { runCatching { render(tones.toList()) } }
    }

    private fun render(tones: List<Tone>) {
        val total = tones.sumOf { it.ms * RATE / 1000 }
        val pcm = ShortArray(total)
        var offset = 0
        for (t in tones) {
            val n = t.ms * RATE / 1000
            val ramp = min(RATE * 4 / 1000, n / 2) // 4 ms fade in/out to avoid clicks
            for (i in 0 until n) {
                if (t.hz <= 0) continue
                val env = when {
                    i < ramp -> i.toDouble() / ramp
                    i > n - ramp -> (n - i).toDouble() / ramp
                    else -> 1.0
                }
                val v = sin(2 * PI * t.hz * i / RATE) * env * 0.32
                pcm[offset + i] = (v * Short.MAX_VALUE).toInt().toShort()
            }
            offset += n
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep((total * 1000L / RATE) + 30)
        } finally {
            track.release()
        }
    }
}
