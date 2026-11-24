package dev.vantara.replicatorops.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Double red alert klaxon: an original rising "whoop" made on the phone (not a recording),
 * played on the alarm stream so it sounds even in Do Not Disturb when that option is on.
 */
object AlertSound {
    private const val RATE = 22050

    private fun whoops(count: Int): ShortArray {
        val one = (RATE * 0.55).toInt(); val gap = (RATE * 0.12).toInt()
        val out = ShortArray((one + gap) * count)
        var phase = 0.0
        for (n in 0 until count) {
            val base = n * (one + gap)
            for (i in 0 until one) {
                val t = i / one.toDouble()
                val f = 520 + 780 * t * t          // sweep up
                phase += 2 * PI * f / RATE
                val env = minOf(1.0, i / (RATE * 0.02)) * minOf(1.0, (one - i) / (RATE * 0.04))
                // A little square in the wave for an urgent edge.
                val v = sin(phase) * 0.75 + (if (sin(phase) > 0) 0.25 else -0.25)
                out[base + i] = (v * env * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    /** Plays the klaxon [times] times. [throughDnd] uses the alarm stream; otherwise notifications. */
    fun play(context: Context, times: Int = 4, throughDnd: Boolean) {
        thread(name = "double-red-alert") {
            runCatching {
                val pcm = whoops(times)
                val usage = if (throughDnd) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / RATE + 200)
                track.release()
            }
        }
        runCatching {
            val vib = if (android.os.Build.VERSION.SDK_INT >= 31)
                (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
            else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
            vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500, 200, 500), -1))
        }
    }
}
