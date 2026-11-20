package dev.ahmedmohamed.hayaitts.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.Engine
import dev.ahmedmohamed.hayaitts.data.voices.parseLocale

/**
 * Invisible activity the system TTS settings call on an engine: CHECK_TTS_DATA
 * (which locales are installed, fills its language list) and GET_SAMPLE_TEXT
 * (the Play button only speaks once this answers).
 */
class TtsDataActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            Engine.ACTION_CHECK_TTS_DATA -> {
                val voices = runCatching { SherpaTtsRuntime.get(applicationContext).listAvailableVoices() }.getOrDefault(emptyList())
                // "eng-GBR": the ISO-3 form the settings app parses.
                val locales = voices.flatMap { it.languages }.map(::parseLocale).filter { it.language.isNotEmpty() }
                    .mapNotNull { l -> runCatching { listOf(l.isO3Language, l.isO3Country).filter(String::isNotEmpty).joinToString("-") }.getOrNull() }
                    .distinct()
                setResult(
                    if (locales.isEmpty()) Engine.CHECK_VOICE_DATA_FAIL else Engine.CHECK_VOICE_DATA_PASS,
                    Intent()
                        .putStringArrayListExtra(Engine.EXTRA_AVAILABLE_VOICES, ArrayList(locales))
                        .putStringArrayListExtra(Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf()),
                )
            }
            Engine.ACTION_GET_SAMPLE_TEXT -> {
                // Other languages: the settings app falls back to its own sample sentence.
                if (intent.getStringExtra("language")?.lowercase() in setOf("en", "eng")) {
                    setResult(TextToSpeech.LANG_AVAILABLE, Intent().putExtra("sampleText", SAMPLE_EN))
                } else {
                    setResult(TextToSpeech.LANG_NOT_SUPPORTED)
                }
            }
        }
        finish()
    }

    private companion object {
        const val SAMPLE_EN = "This is an example of speech synthesis in English with HayaiTTS."
    }
}
