package com.alwaeli.madarchart

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** One decoded chunk: parallel arrays of scaled integers (price * 10^digits, volume * vscale). */
class Decoded(val n: Int, val t: LongArray, val o: LongArray, val h: LongArray, val l: LongArray, val c: LongArray, val v: LongArray)

/**
 * Pack chunk codec. Must match tools/build_pack.py (encode_chunk / decode_chunk) exactly:
 * zlib( six zigzag-varint columns: dt, open - previous close, high - max(o,c), min(o,c) - low, close - open, volume ).
 */
object Codec {
    fun decode(blob: ByteArray, n: Int, first: Long): Decoded {
        val raw = inflate(blob)
        val pos = intArrayOf(0)
        val dt = column(raw, pos, n)
        val dOpen = column(raw, pos, n)
        val dHigh = column(raw, pos, n)
        val dLow = column(raw, pos, n)
        val dClose = column(raw, pos, n)
        val v = column(raw, pos, n)
        val t = LongArray(n); val o = LongArray(n); val h = LongArray(n); val l = LongArray(n); val c = LongArray(n)
        var time = first
        var prevClose = 0L
        for (i in 0 until n) {
            time += dt[i]                       // dt[0] is 0, so t[0] == first
            t[i] = time
            o[i] = prevClose + dOpen[i]
            c[i] = o[i] + dClose[i]
            h[i] = maxOf(o[i], c[i]) + dHigh[i]
            l[i] = minOf(o[i], c[i]) - dLow[i]
            prevClose = c[i]
        }
        return Decoded(n, t, o, h, l, c, v)
    }

    private fun column(raw: ByteArray, pos: IntArray, n: Int): LongArray {
        val out = LongArray(n)
        var p = pos[0]
        for (i in 0 until n) {
            var z = 0L
            var shift = 0
            while (true) {
                val b = raw[p++].toInt() and 0xFF
                z = z or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) break
                shift += 7
            }
            out[i] = (z ushr 1) xor -(z and 1L)
        }
        pos[0] = p
        return out
    }

    private fun inflate(blob: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(blob)
        val out = ByteArrayOutputStream(blob.size * 4)
        val buf = ByteArray(16 * 1024)
        while (!inf.finished()) {
            val k = inf.inflate(buf)
            if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, k)
        }
        inf.end()
        return out.toByteArray()
    }
}
