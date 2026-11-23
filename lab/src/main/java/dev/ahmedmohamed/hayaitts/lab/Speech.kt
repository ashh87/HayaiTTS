package dev.ahmedmohamed.hayaitts.lab

import android.content.Context
import dev.ahmedmohamed.hayaitts.tts.Espeak
import dev.ahmedmohamed.hayaitts.tts.MisakiFrontend
import dev.ahmedmohamed.hayaitts.tts.g2p.MisakiAssets
import dev.ahmedmohamed.hayaitts.tts.g2p.MisakiEnglish
import dev.ahmedmohamed.hayaitts.tts.markup.Markup
import java.io.File
import kotlin.math.pow

/**
 * Text (with markup) to audio, as the kokoro tools' expressive.render(): HayaiTTS's misaki front end
 * (British, custom words, optional accent rules), then [Kokoro] with continuous controls per segment.
 * Not thread-safe (espeak-ng isn't): call from one thread.
 */
class Speech(
    private val context: Context,
    private val kokoro: Kokoro,
    /** Read at each render, so the Mixer's preset sliders apply live. */
    private val presets: Map<String, List<Double>>,
    private val nControls: Int,
    private val maxIntensity: Double,
    private val customWords: File,
) {
    private val g2ps = HashMap<String, MisakiEnglish>()

    private fun g2p(accent: File?): MisakiEnglish = g2ps.getOrPut(accent?.path.orEmpty()) {
        // espeak-ng's British voice is plain "en", as in HayaiTTS's SherpaTtsRuntime.frontendFor
        MisakiAssets.english(context, true, customWords, accent) { Espeak.phonemize(context, it, "en").joinToString(" ") }
    }

    /** expressive.controls(): the emotion's preset scaled by intensity, plus a pitch offset in semitones. */
    fun controls(emotion: String?, intensity: Double, pitch: Double): FloatArray {
        val c = FloatArray(nControls)
        presets[emotion]?.forEachIndexed { i, v -> c[i] = (v * intensity.coerceIn(0.0, maxIntensity)).toFloat() }
        c[0] += pitch.toFloat()
        return c
    }

    fun render(text: String, voice: FloatArray, accent: File?, speed: Float = 1f): FloatArray {
        val frontend = MisakiFrontend(g2p(accent))
        val out = ArrayList<FloatArray>()
        for (seg in Markup.parse(text)) {
            if (seg.text.isNotBlank()) {
                val ctrl = controls(seg.emotion, seg.intensity, seg.pitch)
                val gain = 10.0.pow(seg.volume / 20).toFloat()
                for (chunk in frontend.phonemize(seg.text)) {
                    val a = kokoro.run(chunk, voice, ctrl, (speed * seg.rate).toFloat().coerceIn(0.25f, 4f)).audio
                    if (gain != 1f) for (i in a.indices) a[i] *= gain
                    out += a
                }
            }
            if (seg.pauseMs > 0) out += FloatArray(Kokoro.SAMPLE_RATE * seg.pauseMs / 1000)
        }
        return FloatArray(out.sumOf { it.size }).also { all -> var at = 0; out.forEach { it.copyInto(all, at); at += it.size } }
    }

    /** The phonemes misaki gives each segment, with [accent]'s rules applied when given. */
    fun phonemes(text: String, accent: File?): String =
        Markup.parse(text).filter { it.text.isNotBlank() }.joinToString("  ") { Markup.stripMarkers(g2p(accent).phonemize(it.text)) }
}
