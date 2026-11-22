package dev.ahmedmohamed.hayaitts.lab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The kokoro tools' lab_server.py: index, parts (cached under [cache]), saved mixes and verdicts. */
class LabServer(base: String, private val cache: File) {
    private val base: HttpUrl = base.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }.toHttpUrl()
    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.MINUTES).build()

    @Serializable
    data class Index(
        val model: Stamp,
        val voices: List<Voice>,
        @SerialName("default_voice") val defaultVoice: String,
        /** The voice in the bundle HayaiTTS has installed (build/bf_ha-expressive), if it is one of [voices]. */
        val installed: String? = null,
        val accents: List<String>,
        val presets: Map<String, List<Double>>,
        @SerialName("n_controls") val nControls: Int,
        @SerialName("max_intensity") val maxIntensity: Double,
        val clips: List<ClipFolder>,
        /** The lab app as last built on the PC, for the self-update. */
        val app: Stamp? = null,
    )

    @Serializable data class Stamp(val size: Long, val mtime: Long, val sha256: String? = null)

    @Serializable data class Voice(val name: String, val group: String, val mtime: Long)

    @Serializable data class ClipFolder(val name: String, val root: Int, val files: List<String>)

    private fun url(vararg segments: String) = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()

    private fun get(url: HttpUrl) = http.newCall(Request.Builder().url(url).build()).execute().also {
        if (!it.isSuccessful) { it.close(); throw IOException("${it.code} for $url") }
    }

    fun index(): Index = get(url("index.json")).use { JSON.decodeFromString(it.body!!.string()) }

    fun bytes(vararg segments: String): ByteArray = get(url(*segments)).use { it.body!!.bytes() }

    /**
     * [segments]' file, from the cache when its [stamp] (anything that changes with the file) matches,
     * else downloaded; [progress] gets the fraction done.
     */
    fun file(name: String, stamp: String?, vararg segments: String, progress: (Float) -> Unit = {}): File {
        val dest = File(cache, name)
        val stampFile = File(cache, "$name.stamp")
        if (stamp != null && dest.isFile && stampFile.isFile && stampFile.readText() == stamp) return dest
        cache.mkdirs()
        val tmp = File(cache, "$name.part")
        get(url(*segments)).use { r ->
            val body = r.body!!
            val total = body.contentLength().coerceAtLeast(1)
            var done = 0L
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done.toFloat() / total)
                    }
                }
            }
        }
        if (!tmp.renameTo(dest)) throw IOException("could not save $dest")
        if (stamp != null) stampFile.writeText(stamp) else stampFile.delete()
        return dest
    }

    fun post(path: String, body: JsonElement): String =
        http.newCall(
            Request.Builder().url(url(path)).post(body.toString().toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("${r.code}: $text")
            text
        }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
