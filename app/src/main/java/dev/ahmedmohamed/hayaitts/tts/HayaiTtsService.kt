package dev.ahmedmohamed.hayaitts.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import co.touchlab.kermit.Logger
import dev.ahmedmohamed.hayaitts.data.playground.VoiceTuning
import dev.ahmedmohamed.hayaitts.data.playground.VoiceTuningRepository
import dev.ahmedmohamed.hayaitts.data.voices.parseLocale
import dev.ahmedmohamed.hayaitts.domain.model.InstalledVoice
import dev.ahmedmohamed.hayaitts.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.util.Locale
import kotlin.concurrent.thread

/**
 * System-wide TTS engine. Iterates every voice the user has installed
 * (Room), exposing each one to the TTS framework via [onGetVoices].
 *
 * Locale resolution: when the framework picks a [language/country] the
 * framework calls [onIsLanguageAvailable]; we return `LANG_AVAILABLE` if any
 * installed voice tags it (BCP-47 prefix match). The actual voice picked is
 * decided by [SynthesisRequest.voiceName].
 */
class HayaiTtsService : TextToSpeechService() {

    private val log = Logger.withTag("HayaiTtsService")

    @Volatile private var stopRequested: Boolean = false
    @Volatile private var lastSelectedVoiceId: String = ""

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val voices = runtimeOrNull()?.listAvailableVoices().orEmpty()
        val requested = buildBcp47(lang, country, variant)
        return when {
            requested.isEmpty() -> TextToSpeech.LANG_NOT_SUPPORTED
            voices.any { it.matchesLocale(requested) } -> TextToSpeech.LANG_AVAILABLE
            voices.any { it.matchesLanguage(lang) } -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    // The framework calls this when a client connects (default locale) and on setLanguage,
    // usually well before the first speak(): warm the phonemizer then.
    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            resolveVoiceName(onGetDefaultVoiceNameFor(lang, country, variant))
                ?.let { warm(it, buildBcp47(lang, country, variant).ifBlank { null }) }
        }
        return result
    }

    /**
     * The framework's TextToSpeech.setLanguage() fails unless this names a voice. The
     * default implementation offers the locale tag ("en-GB"), which is not one of ours.
     * Prefers the voice last loaded, then an exact locale match, then same language.
     */
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        val voices = runtimeOrNull()?.listAvailableVoices().orEmpty()
        val requested = buildBcp47(lang, country, variant)
        val (lastId, _) = parseVoiceName(lastSelectedVoiceId)
        voices.firstOrNull { it.voiceId == lastId && it.matchesLanguage(lang) }?.let { return lastSelectedVoiceId }
        // A bare voiceId is valid (onIsValidVoiceName); multi-speaker voices then use the default speaker.
        return (voices.firstOrNull { it.matchesLocale(requested) } ?: voices.firstOrNull { it.matchesLanguage(lang) })?.voiceId
    }

    override fun onGetLanguage(): Array<String> {
        // The framework expects ISO-639 alpha-3 + ISO-3166 alpha-3 + variant.
        // For the currently selected voice, derive these from its first
        // language tag. Defaults to eng/USA so the framework treats the engine
        // as English-capable when no synthesis has run yet.
        val voice = runtimeOrNull()?.listAvailableVoices()?.firstOrNull { it.voiceId == lastSelectedVoiceId }
        val tag = voice?.languages?.firstOrNull() ?: "en-US"
        val locale = parseLocale(tag)
        return arrayOf(locale.isO3Language.ifEmpty { "eng" }, runCatching { locale.isO3Country }.getOrDefault("USA"), "")
    }

    override fun onGetVoices(): List<Voice> {
        val voices = runtimeOrNull()?.listAvailableVoices().orEmpty()
        return voices.flatMap { voice ->
            if (voice.speakers.size > 1) {
                voice.speakers.map { speaker ->
                    val voiceName = "${voice.voiceId}#${speaker.id}#${speaker.name}"
                    val locale = parseLocale(voice.languages.firstOrNull() ?: "en-US")
                    Voice(
                        voiceName,
                        locale,
                        Voice.QUALITY_NORMAL,
                        Voice.LATENCY_NORMAL,
                        /* requiresNetworkConnection = */ false,
                        /* features = */ emptySet(),
                    )
                }
            } else {
                listOf(voice.toFrameworkVoice())
            }
        }
    }

    override fun onIsValidVoiceName(name: String?): Int =
        if (resolveVoiceName(name) != null) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(name: String?): Int {
        val voice = resolveVoiceName(name) ?: return TextToSpeech.ERROR
        lastSelectedVoiceId = name!!
        warm(voice, voice.languages.firstOrNull())
        return TextToSpeech.SUCCESS
    }

    /**
     * Loads the voice's model (and misaki's tables) in the background with the settings
     * onSynthesizeText will use, so the first speak() finds it in memory. A speak() that
     * arrives mid-load waits on the same load rather than starting another.
     */
    private fun warm(voice: InstalledVoice, languageHint: String?) {
        val rt = runtimeOrNull() ?: return
        rt.preloadFrontends(listOf(voice))
        thread(name = "engine-preload", isDaemon = true) {
            val t = tuningFor(voice.voiceId)
            runCatching { rt.sampleRateOf(voice.voiceId, languageHint, t.lengthScale, t.noiseScale, t.noiseScaleW) }
                .onFailure { log.w(it) { "Preloading ${voice.voiceId} failed" } }
        }
    }

    private fun tuningFor(voiceId: String): VoiceTuning =
        runCatching { GlobalContext.get().get<VoiceTuningRepository>() }.getOrNull()?.let { repo ->
            runCatching { runBlocking { repo.tuningFor(voiceId).first() } }.getOrNull()
        } ?: VoiceTuning.Default

    /**
     * Parse a framework voice name and validate it against the installed
     * catalog. Returns the resolved [InstalledVoice] (and optionally the
     * speaker id parsed from a `voiceId#speakerId#name` form) on success, or
     * null if the name is malformed or unknown.
     */
    private fun resolveVoiceName(name: String?): InstalledVoice? {
        if (name.isNullOrEmpty()) return null
        val (voiceId, speakerId) = parseVoiceName(name)
        val voice = runtimeOrNull()?.listAvailableVoices().orEmpty()
            .firstOrNull { it.voiceId == voiceId } ?: return null
        if (speakerId != null && voice.speakers.none { it.id == speakerId }) return null
        return voice
    }

    /**
     * Voice names exposed via [onGetVoices] use the form
     * `voiceId#speakerId#speakerName` for multi-speaker models and plain
     * `voiceId` otherwise. This helper recovers both parts.
     */
    private fun parseVoiceName(name: String): Pair<String, Int?> {
        if (name.isEmpty()) return "" to null
        val parts = name.split("#", limit = 3)
        val voiceId = parts[0]
        val speakerId = parts.getOrNull(1)?.toIntOrNull()
        return voiceId to speakerId
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false

        val rawText = request.charSequenceText?.toString().orEmpty()
        val text = if (rawText.length > MAX_TEXT_LENGTH) {
            rawText.substring(0, MAX_TEXT_LENGTH)
        } else rawText

        val runtime = try {
            SherpaTtsRuntime.get(applicationContext)
        } catch (t: Throwable) {
            log.e(t) { "Failed to initialise SherpaTtsRuntime" }
            callback.error()
            return
        }

        val requestVoiceName = request.voiceName ?: lastSelectedVoiceId
        val requestLanguageHint = buildBcp47(
            request.language,
            request.country,
            request.variant,
        ).ifBlank { null }
        val (selectedVoiceId, selectedSpeakerId) = parseVoiceName(requestVoiceName)
        val (fallbackVoiceId, _) = parseVoiceName(lastSelectedVoiceId)

        val candidates = runtime.listAvailableVoices()
        val voice = candidates.firstOrNull { it.voiceId == selectedVoiceId }
            ?: candidates.firstOrNull { it.voiceId == fallbackVoiceId }
            ?: candidates.firstOrNull { it.bundled }
            ?: candidates.firstOrNull()
            ?: run {
                log.e { "No voices available" }
                callback.error()
                return
            }

        if (text.isEmpty()) {
            if (callback.start(voice.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, 1) ==
                TextToSpeech.SUCCESS
            ) callback.done()
            return
        }

        val tuning = tuningFor(voice.voiceId)

        val requestSpeed = (request.speechRate.toFloat() / 100f).coerceIn(0.5f, 2.0f)
        val speed = (requestSpeed * tuning.speed).coerceIn(0.5f, 2.0f)

        val settingsRepo = runCatching { GlobalContext.get().get<SettingsRepository>() }.getOrNull()
        val defaultSpeaker = settingsRepo?.let { repo ->
            runCatching { runBlocking { repo.defaultSpeakerByVoice.first()[voice.voiceId] } }.getOrNull()
        }

        val sid = selectedSpeakerId
            ?: request.params?.getString("speakerId")?.toIntOrNull()
            ?: defaultSpeaker
            ?: voice.speakers.firstOrNull()?.id
            ?: 0

        val sampleRate = try {
            // Same settings as the synthesis below, so this loads (or reuses) that one engine.
            runtime.sampleRateOf(voice.voiceId, requestLanguageHint, tuning.lengthScale, tuning.noiseScale, tuning.noiseScaleW)
        } catch (t: Throwable) {
            log.e(t) { "Could not load voice ${voice.voiceId}" }
            callback.error()
            return
        }

        if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, /* channelCount = */ 1)
            != TextToSpeech.SUCCESS
        ) {
            return
        }

        // Markup (docs/MARKUP.md) is honoured; each sentence plays as soon as it is generated and a 0 return stops early.
        try {
            runtime.synthesizeMarkup(
                voiceId = voice.voiceId,
                text = text,
                sid = sid,
                speed = speed,
                pitch = tuning.pitch,
                lengthScale = tuning.lengthScale,
                noiseScale = tuning.noiseScale,
                noiseScaleW = tuning.noiseScaleW,
                languageHint = requestLanguageHint,
            ) { chunk -> if (streamPcm(callback, chunk)) 1 else 0 }
        } catch (t: Throwable) {
            log.e(t) { "sherpa-onnx synthesis failed" }
            callback.error()
            return
        }
        callback.done()
    }

    private fun runtimeOrNull(): SherpaTtsRuntime? = runCatching {
        SherpaTtsRuntime.get(applicationContext)
    }.getOrNull()

    private fun InstalledVoice.toFrameworkVoice(): Voice {
        val locale = parseLocale(languages.firstOrNull() ?: "en-US")
        return Voice(
            voiceId,
            locale,
            Voice.QUALITY_NORMAL,
            Voice.LATENCY_NORMAL,
            /* requiresNetworkConnection = */ false,
            /* features = */ emptySet(),
        )
    }

    private fun InstalledVoice.matchesLocale(bcp47: String): Boolean = languages.any {
        it.equals(bcp47, ignoreCase = true)
    }

    private fun InstalledVoice.matchesLanguage(lang: String?): Boolean {
        if (lang.isNullOrEmpty()) return false
        val target = parseLocale(frameworkLocaleTag(lang, null)).language
        if (target.isEmpty()) return false
        return languages.any { parseLocale(it).language.equals(target, ignoreCase = true) }
    }

    /**
     * Best-effort BCP-47 builder for the (lang, country, variant) triple the
     * framework hands us. The framework uses ISO-639 alpha-3 for lang and
     * ISO-3166 alpha-3 for country; we convert via [Locale] so the comparison
     * with the catalog's BCP-47 tags (alpha-2) works.
     */
    private fun buildBcp47(lang: String?, country: String?, variant: String?): String = frameworkLocaleTag(lang, country)

    /**
     * Convert sherpa-onnx's float samples (-1..1) to little-endian 16-bit PCM
     * and stream them through [SynthesisCallback.audioAvailable] in chunks no
     * larger than [SynthesisCallback.maxBufferSize]. False once playback was stopped.
     */
    private fun streamPcm(callback: SynthesisCallback, samples: FloatArray): Boolean {
        val maxBufferBytes = callback.maxBufferSize.coerceAtLeast(BYTES_PER_SAMPLE * 64)
        val chunkBytes = (maxBufferBytes and 0x7FFFFFFE)
        val samplesPerChunk = chunkBytes / BYTES_PER_SAMPLE
        val buffer = ByteArray(chunkBytes)

        var sampleCursor = 0
        while (sampleCursor < samples.size && !stopRequested) {
            val remaining = samples.size - sampleCursor
            val take = minOf(samplesPerChunk, remaining)
            var writeIdx = 0
            for (i in 0 until take) {
                val f = samples[sampleCursor + i]
                val clamped = when {
                    f >= 1.0f -> Short.MAX_VALUE.toInt()
                    f <= -1.0f -> Short.MIN_VALUE.toInt()
                    else -> (f * Short.MAX_VALUE).toInt()
                }
                buffer[writeIdx] = (clamped and 0xFF).toByte()
                buffer[writeIdx + 1] = ((clamped shr 8) and 0xFF).toByte()
                writeIdx += 2
            }
            if (callback.audioAvailable(buffer, 0, writeIdx) != TextToSpeech.SUCCESS) return false
            sampleCursor += take
        }
        return !stopRequested
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val MAX_TEXT_LENGTH = 2000
    }
}

/**
 * BCP-47 tag for the framework's locale parts. TextToSpeech sends ISO-639-2 / ISO-3166
 * alpha-3 codes ("eng", "GBR"); voices are tagged with the two-letter forms ("en-GB").
 */
internal fun frameworkLocaleTag(lang: String?, country: String?): String {
    if (lang.isNullOrBlank()) return ""
    val l = lang.trim().replace('_', '-').substringBefore('-').lowercase().let { ISO3_LANGUAGES[it] ?: it }
    val c = country?.trim()?.uppercase()?.let { ISO3_COUNTRIES[it] ?: it }.orEmpty()
    return runCatching { Locale.Builder().setLanguage(l).setRegion(c).build().toLanguageTag() }
        .getOrElse { runCatching { Locale.Builder().setLanguage(l).build().toLanguageTag() }.getOrDefault("") }
}

private val ISO3_LANGUAGES: Map<String, String> by lazy {
    Locale.getISOLanguages().mapNotNull { runCatching { Locale(it).isO3Language to it }.getOrNull() }.toMap()
}

private val ISO3_COUNTRIES: Map<String, String> by lazy {
    Locale.getISOCountries().mapNotNull { runCatching { Locale("", it).isO3Country to it }.getOrNull() }.toMap()
}
