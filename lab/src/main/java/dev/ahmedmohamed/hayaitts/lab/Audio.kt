package dev.ahmedmohamed.hayaitts.lab

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A clip to play: mono float samples at [rate] Hz. */
class Clip(val samples: FloatArray, val rate: Int) {
    val millis: Long get() = samples.size * 1000L / rate
}

/** One clip at a time through AudioTrack; [play] returns at once. */
class Player {
    private var track: AudioTrack? = null

    @Synchronized
    fun play(clip: Clip) {
        stop()
        if (clip.samples.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(clip.rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(clip.samples.size * 4)
            .build()
        t.write(clip.samples, 0, clip.samples.size, AudioTrack.WRITE_BLOCKING)
        t.play()
        track = t
    }

    @Synchronized
    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}

/** RIFF WAV (PCM 16/24/32-bit or float, any channel count, mixed down to mono) to a [Clip]. */
fun readWav(bytes: ByteArray): Clip {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
    var pos = 12
    var format = 0
    var channels = 1
    var rate = 0
    var bits = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val len = b.getInt(pos + 4)
        val body = pos + 8
        if (id == "fmt ") {
            format = b.getShort(body).toInt() and 0xffff
            channels = b.getShort(body + 2).toInt()
            rate = b.getInt(body + 4)
            bits = b.getShort(body + 14).toInt()
            if (format == 0xFFFE) format = b.getShort(body + 24).toInt() // WAVE_FORMAT_EXTENSIBLE: the sub-format
        } else if (id == "data") {
            val width = bits / 8
            val end = minOf(bytes.size, body + len)
            val frames = (end - body) / (width * channels)
            val out = FloatArray(frames) { f ->
                var s = 0f
                for (c in 0 until channels) {
                    val at = body + (f * channels + c) * width
                    s += when {
                        format == 3 && width == 4 -> b.getFloat(at)
                        width == 2 -> b.getShort(at) / 32768f
                        width == 3 -> ((bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or (bytes[at + 2].toInt() shl 16)) / 8388608f
                        width == 4 -> b.getInt(at) / 2147483648f
                        else -> error("unsupported WAV: $bits-bit format $format")
                    }
                }
                s / channels
            }
            return Clip(out, rate)
        }
        pos = body + len + (len and 1)
    }
    error("WAV without data")
}
