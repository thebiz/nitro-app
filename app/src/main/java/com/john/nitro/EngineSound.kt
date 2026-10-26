package com.john.nitro

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max

/**
 * Synthetischer Motorsound ohne Audiodateien:
 * Tonhöhe folgt dem Tempo, Nitro rauscht, Kollisionen knallen kurz.
 */
class EngineSound {
    @Volatile var rpm = 0f        // 0 … 1
    @Volatile var load = 0f       // 0 … 1, Gas gedrückt
    @Volatile var nitro = false
    @Volatile var volume = 1f
    @Volatile private var bumpAmp = 0f
    @Volatile private var running = false
    private var thread: Thread? = null

    fun bump(strength: Float) {
        bumpAmp = max(bumpAmp, strength.coerceIn(0f, 1f))
    }

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread({ loop() }, "Nitro-Sound").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    private fun loop() {
        val rate = 22050
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            return
        }
        track.play()
        val buf = ShortArray(441)
        val rnd = java.util.Random()
        var phase = 0.0
        var phase2 = 0.0
        var lp = 0f
        var noiseLp = 0f
        var freq = 40f
        var vol = 0f
        var hiss = 0f
        while (running) {
            val r = rpm
            val targetF = 36f + r * 150f + (if (nitro) 18f else 0f)
            val targetVol = (0.16f + 0.14f * load + 0.12f * r) * volume
            val targetHiss = if (nitro) 0.14f * volume else 0f
            val cutoff = 0.07f + r * 0.22f + load * 0.05f
            for (i in buf.indices) {
                freq += (targetF - freq) * 0.0012f
                vol += (targetVol - vol) * 0.0008f
                hiss += (targetHiss - hiss) * 0.002f
                phase += freq / rate
                if (phase >= 1.0) phase -= 1.0
                phase2 += freq * 2.01 / rate
                if (phase2 >= 1.0) phase2 -= 1.0
                // Zündimpulse: Mischung aus Sägezahn und schmalem Puls
                val saw = (phase * 2.0 - 1.0).toFloat()
                val pulse = if (phase < 0.28) 1f else -0.35f
                var s = saw * 0.45f + pulse * 0.55f + (phase2 * 2.0 - 1.0).toFloat() * 0.22f
                s += (rnd.nextFloat() * 2f - 1f) * 0.08f
                lp += (s - lp) * cutoff
                var out = lp * vol
                if (hiss > 0.001f) {
                    noiseLp += ((rnd.nextFloat() * 2f - 1f) - noiseLp) * 0.4f
                    out += noiseLp * hiss
                }
                val b = bumpAmp
                if (b > 0.001f) {
                    out += (rnd.nextFloat() * 2f - 1f) * b * 0.55f
                    bumpAmp = b * 0.9993f
                }
                buf[i] = (out.coerceIn(-1f, 1f) * 30000f).toInt().toShort()
            }
            track.write(buf, 0, buf.size)
        }
        try {
            track.pause()
            track.flush()
            track.release()
        } catch (_: Exception) {
        }
    }
}
