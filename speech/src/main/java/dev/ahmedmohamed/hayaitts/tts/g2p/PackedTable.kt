package dev.ahmedmohamed.hayaitts.tts.g2p

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Read-only string → bytes table, binary-searched in place over a memory-mapped
 * file, so the data costs no heap and nothing is parsed at load. Written by
 * tools/g2p/build_assets.py (write_kv): big-endian "HKV1", record count, record
 * offsets, then records (u16 key length, UTF-8 key, u16 value length, value)
 * sorted by UTF-8 key bytes. Only absolute reads are used, so it is thread-safe.
 */
class PackedTable(private val buf: ByteBuffer) {
    private val size: Int

    init {
        require(buf.getInt(0) == MAGIC) { "not a packed table" }
        size = buf.getInt(4)
    }

    /** Value bytes for [key] (a read-only view), or null. */
    operator fun get(key: String): ByteBuffer? {
        val k = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val rec = buf.getInt(8 + 4 * mid)
            val c = compareKey(rec, k)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> {
                    val v = rec + 2 + k.size
                    val len = buf.getShort(v).toInt() and 0xFFFF
                    return buf.duplicate().apply { position(v + 2); limit(v + 2 + len) }.slice()
                }
            }
        }
        return null
    }

    fun string(key: String): String? = get(key)?.let { Charsets.UTF_8.decode(it).toString() }

    operator fun contains(key: String): Boolean = get(key) != null

    /** Pull the pages into the page cache ahead of the first lookups. */
    fun preload() {
        (buf as? MappedByteBuffer)?.load()
    }

    private fun compareKey(rec: Int, k: ByteArray): Int {
        val len = buf.getShort(rec).toInt() and 0xFFFF
        for (i in 0 until minOf(len, k.size)) {
            val d = (buf.get(rec + 2 + i).toInt() and 0xFF) - (k[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return len - k.size
    }

    companion object {
        private const val MAGIC = 0x484B5631 // "HKV1"

        /** Maps an asset stored uncompressed (androidResources.noCompress "kv"). */
        fun asset(context: Context, name: String): PackedTable {
            val fd = context.assets.openFd(name)
            return fd.createInputStream().use { PackedTable(it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)) }
        }

        fun file(file: File): PackedTable =
            FileInputStream(file).use { PackedTable(it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())) }
    }
}
