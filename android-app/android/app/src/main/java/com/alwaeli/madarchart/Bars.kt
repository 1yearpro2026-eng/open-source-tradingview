package com.alwaeli.madarchart

/** Candles as parallel arrays, oldest first. `t` is epoch SECONDS (what the web UI expects). */
class Bars(val t: LongArray, val o: DoubleArray, val h: DoubleArray, val l: DoubleArray, val c: DoubleArray, val v: DoubleArray) {
    val size: Int get() = t.size
    fun isEmpty() = t.isEmpty()
    fun lastTime(): Long = t[t.size - 1]

    /** Newest `n` candles. */
    fun tail(n: Int): Bars = if (n >= size) this else slice(size - n, size)

    fun slice(from: Int, to: Int) = Bars(t.copyOfRange(from, to), o.copyOfRange(from, to), h.copyOfRange(from, to), l.copyOfRange(from, to), c.copyOfRange(from, to), v.copyOfRange(from, to))

    /** Candles with time <= toSec. */
    fun upTo(toSec: Long): Bars {
        var lo = 0; var hi = size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (t[mid] <= toSec) lo = mid + 1 else hi = mid }
        return if (lo == size) this else slice(0, lo)
    }

    /** Candles with time < sec. */
    fun before(sec: Long): Bars = upTo(sec - 1)

    fun json(): String {
        val sb = StringBuilder(size * 96 + 2).append('[')
        for (i in 0 until size) {
            if (i > 0) sb.append(',')
            sb.append("{\"time\":").append(t[i]).append(",\"open\":").append(o[i]).append(",\"high\":").append(h[i])
                .append(",\"low\":").append(l[i]).append(",\"close\":").append(c[i]).append(",\"volume\":").append(v[i]).append('}')
        }
        return sb.append(']').toString()
    }

    fun barJson(i: Int) = "{\"time\":${t[i]},\"open\":${o[i]},\"high\":${h[i]},\"low\":${l[i]},\"close\":${c[i]},\"volume\":${v[i]}}"

    companion object {
        val EMPTY = Bars(LongArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))

        /** Union of two series; on the same time `b` wins. Result sorted by time, no duplicates. */
        fun merge(a: Bars, b: Bars): Bars {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val n = a.size + b.size
            val t = LongArray(n); val o = DoubleArray(n); val h = DoubleArray(n); val l = DoubleArray(n); val c = DoubleArray(n); val v = DoubleArray(n)
            var i = 0; var j = 0; var k = 0
            fun put(src: Bars, x: Int) { t[k] = src.t[x]; o[k] = src.o[x]; h[k] = src.h[x]; l[k] = src.l[x]; c[k] = src.c[x]; v[k] = src.v[x]; k++ }
            while (i < a.size || j < b.size) {
                when {
                    j >= b.size -> put(a, i++)
                    i >= a.size -> put(b, j++)
                    a.t[i] < b.t[j] -> put(a, i++)
                    a.t[i] > b.t[j] -> put(b, j++)
                    else -> { put(b, j++); i++ }
                }
            }
            return Bars(t.copyOf(k), o.copyOf(k), h.copyOf(k), l.copyOf(k), c.copyOf(k), v.copyOf(k))
        }

        fun concat(older: Bars, newer: Bars): Bars = merge(older, newer)

        fun of(list: List<DoubleArray>): Bars {      // rows of [time, o, h, l, c, v]
            val n = list.size
            return Bars(LongArray(n) { list[it][0].toLong() }, DoubleArray(n) { list[it][1] }, DoubleArray(n) { list[it][2] },
                DoubleArray(n) { list[it][3] }, DoubleArray(n) { list[it][4] }, DoubleArray(n) { list[it][5] })
        }
    }
}
