package com.tvcast.airplay

import java.nio.ByteBuffer
import java.util.Locale

/** Minimal Apple property-list support: XML writer + binary (bplist00) reader. */
object Plist {

    fun toXml(root: Any?): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        sb.append("<plist version=\"1.0\">\n")
        write(sb, root)
        sb.append("</plist>\n")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("<string></string>\n")
            is Map<*, *> -> {
                sb.append("<dict>\n")
                for ((k, value) in v) {
                    sb.append("<key>").append(esc(k.toString())).append("</key>\n")
                    write(sb, value)
                }
                sb.append("</dict>\n")
            }
            is List<*> -> {
                sb.append("<array>\n")
                v.forEach { write(sb, it) }
                sb.append("</array>\n")
            }
            is Boolean -> sb.append(if (v) "<true/>\n" else "<false/>\n")
            is Int, is Long, is Short -> sb.append("<integer>").append(v).append("</integer>\n")
            is Float, is Double -> sb.append("<real>")
                .append(String.format(Locale.US, "%.6f", (v as Number).toDouble())).append("</real>\n")
            else -> sb.append("<string>").append(esc(v.toString())).append("</string>\n")
        }
    }

    fun isBinary(data: ByteArray): Boolean =
        data.size > 40 && String(data, 0, 6, Charsets.US_ASCII) == "bplist"

    /** Parses a binary plist. Returns Map / List / String / Long / Double / Boolean / ByteArray. */
    fun parseBinary(d: ByteArray): Any? {
        val t = d.size - 32
        val offSize = d[t + 6].toInt() and 0xff
        val refSize = d[t + 7].toInt() and 0xff
        val numObjects = readUInt(d, t + 8, 8).toInt()
        val top = readUInt(d, t + 16, 8).toInt()
        val tableOff = readUInt(d, t + 24, 8).toInt()
        val offsets = IntArray(numObjects) { readUInt(d, tableOff + it * offSize, offSize).toInt() }

        fun obj(index: Int, depth: Int): Any? {
            if (depth > 32 || index !in offsets.indices) return null
            var off = offsets[index]
            val marker = d[off].toInt() and 0xff
            val hi = marker shr 4
            val lo = marker and 0x0f
            off++

            fun length(): Int {
                if (lo != 0x0f) return lo
                val m = d[off].toInt() and 0xff
                val n = 1 shl (m and 0x0f)
                val len = readUInt(d, off + 1, n).toInt()
                off += 1 + n
                return len
            }

            return when (hi) {
                0x0 -> when (marker) { 0x08 -> false; 0x09 -> true; else -> null }
                0x1 -> {
                    val n = 1 shl lo
                    if (n == 8) ByteBuffer.wrap(d, off, 8).long else readUInt(d, off, n)
                }
                0x2 -> if (lo == 2) ByteBuffer.wrap(d, off, 4).float.toDouble() else ByteBuffer.wrap(d, off, 8).double
                0x3 -> ByteBuffer.wrap(d, off, 8).double
                0x4 -> { val len = length(); d.copyOfRange(off, off + len) }
                0x5 -> { val len = length(); String(d, off, len, Charsets.US_ASCII) }
                0x6 -> { val len = length(); String(d, off, len * 2, Charsets.UTF_16BE) }
                0x8 -> readUInt(d, off, lo + 1)
                0xA -> {
                    val len = length()
                    List(len) { i -> obj(readUInt(d, off + i * refSize, refSize).toInt(), depth + 1) }
                }
                0xD -> {
                    val len = length()
                    val map = LinkedHashMap<String, Any?>()
                    for (i in 0 until len) {
                        val k = obj(readUInt(d, off + i * refSize, refSize).toInt(), depth + 1)
                        val v = obj(readUInt(d, off + (len + i) * refSize, refSize).toInt(), depth + 1)
                        map[k.toString()] = v
                    }
                    map
                }
                else -> null
            }
        }
        return obj(top, 0)
    }

    private fun readUInt(d: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or (d[off + i].toLong() and 0xff)
        return v
    }
}
