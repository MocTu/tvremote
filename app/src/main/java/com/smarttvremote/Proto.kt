package com.smarttvremote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Protobuf tối giản (chỉ varint và length-delimited) đủ cho giao thức Android TV Remote v2. */
object Proto {
    class W {
        private val o = ByteArrayOutputStream()
        private fun varint(v0: Long) {
            var v = v0
            while (v > 0x7F) { o.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
            o.write(v.toInt())
        }
        fun int(field: Int, v: Long): W { varint((field shl 3).toLong()); varint(v); return this }
        fun bytes(field: Int, b: ByteArray): W {
            varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); o.write(b); return this
        }
        fun str(field: Int, s: String): W = bytes(field, s.toByteArray(Charsets.UTF_8))
        fun toBytes(): ByteArray = o.toByteArray()
    }

    /** field -> danh sách giá trị (Long cho varint, ByteArray cho length-delimited). */
    fun parse(b: ByteArray): Map<Int, List<Any>> {
        val m = HashMap<Int, MutableList<Any>>()
        var i = 0
        fun rv(): Long {
            var r = 0L; var s = 0
            while (true) {
                val x = b[i++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if ((x and 0x80) == 0) break
                s += 7
            }
            return r
        }
        while (i < b.size) {
            val tag = rv().toInt()
            val f = tag ushr 3
            when (tag and 7) {
                0 -> m.getOrPut(f) { mutableListOf() }.add(rv())
                2 -> {
                    val n = rv().toInt()
                    m.getOrPut(f) { mutableListOf() }.add(b.copyOfRange(i, i + n)); i += n
                }
                1 -> i += 8
                5 -> i += 4
                else -> throw IOException("wire type không hợp lệ")
            }
        }
        return m
    }

    fun long(m: Map<Int, List<Any>>, f: Int): Long? = m[f]?.firstOrNull() as? Long
    fun sub(m: Map<Int, List<Any>>, f: Int): Map<Int, List<Any>>? = (m[f]?.firstOrNull() as? ByteArray)?.let { parse(it) }

    fun writeFrame(o: OutputStream, payload: ByteArray) {
        val head = ByteArrayOutputStream()
        var v = payload.size
        while (v > 0x7F) { head.write((v and 0x7F) or 0x80); v = v ushr 7 }
        head.write(v)
        head.write(payload)
        o.write(head.toByteArray())
        o.flush()
    }

    /** Trả về null khi đầu bên kia đóng kết nối. */
    fun readFrame(inp: InputStream): ByteArray? {
        var len = 0; var s = 0
        while (true) {
            val x = inp.read()
            if (x < 0) return null
            len = len or ((x and 0x7F) shl s)
            if ((x and 0x80) == 0) break
            s += 7
            if (s > 28) throw IOException("độ dài khung quá lớn")
        }
        if (len < 0 || len > 1_000_000) throw IOException("độ dài khung không hợp lệ")
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(buf, off, len - off)
            if (n < 0) return null
            off += n
        }
        return buf
    }
}
