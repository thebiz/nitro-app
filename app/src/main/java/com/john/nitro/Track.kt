package com.john.nitro

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Strecken-Kontrollpunkte (x, y). Index 0 liegt auf der Start-/Zielgeraden. */
object Tracks {
    val first = floatArrayOf(
        1700f, 2600f, 2800f, 2600f, 3450f, 2480f, 3780f, 2050f, 3620f, 1600f,
        3150f, 1480f, 2700f, 1720f, 2250f, 1760f, 1950f, 1450f, 2150f, 1050f, 2700f, 920f,
        3300f, 920f, 3680f, 680f, 3560f, 280f, 3000f, 200f, 2000f, 260f, 1150f, 300f,
        620f, 580f, 660f, 1040f, 1120f, 1300f, 1250f, 1650f, 1120f, 1930f, 700f, 2060f,
        430f, 2330f, 520f, 2580f, 850f, 2620f
    )
}

/**
 * Geschlossene Strecke: Catmull-Rom-Kurve durch die Kontrollpunkte,
 * gleichmäßig in Punkte mit ca. 12 Einheiten Abstand aufgeteilt.
 */
class Track(ctrl: FloatArray, val width: Float) {
    val half = width / 2f
    val n: Int
    val xs: FloatArray
    val ys: FloatArray
    val nx: FloatArray
    val ny: FloatArray
    var minX = Float.MAX_VALUE; private set
    var minY = Float.MAX_VALUE; private set
    var maxX = -Float.MAX_VALUE; private set
    var maxY = -Float.MAX_VALUE; private set

    init {
        val m = ctrl.size / 2
        val steps = 24
        val cnt = m * steps
        val rx = FloatArray(cnt)
        val ry = FloatArray(cnt)
        var k = 0
        for (i in 0 until m) {
            val i0 = (i - 1 + m) % m
            val i2 = (i + 1) % m
            val i3 = (i + 2) % m
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                rx[k] = cr(ctrl[i0 * 2], ctrl[i * 2], ctrl[i2 * 2], ctrl[i3 * 2], t)
                ry[k] = cr(ctrl[i0 * 2 + 1], ctrl[i * 2 + 1], ctrl[i2 * 2 + 1], ctrl[i3 * 2 + 1], t)
                k++
            }
        }
        val cum = FloatArray(cnt + 1)
        for (i in 0 until cnt) {
            val j = (i + 1) % cnt
            cum[i + 1] = cum[i] + hypot(rx[j] - rx[i], ry[j] - ry[i])
        }
        val total = cum[cnt]
        n = max(16, (total / 12f).roundToInt())
        xs = FloatArray(n)
        ys = FloatArray(n)
        nx = FloatArray(n)
        ny = FloatArray(n)
        var seg = 0
        for (p in 0 until n) {
            val d = total * p / n
            while (seg < cnt - 1 && cum[seg + 1] < d) seg++
            val j = (seg + 1) % cnt
            val len = cum[seg + 1] - cum[seg]
            val f = if (len > 0f) (d - cum[seg]) / len else 0f
            xs[p] = rx[seg] + (rx[j] - rx[seg]) * f
            ys[p] = ry[seg] + (ry[j] - ry[seg]) * f
        }
        for (p in 0 until n) {
            val a = (p - 1 + n) % n
            val b = (p + 1) % n
            var tx = xs[b] - xs[a]
            var ty = ys[b] - ys[a]
            val l = hypot(tx, ty)
            tx /= l; ty /= l
            nx[p] = -ty
            ny[p] = tx
            if (xs[p] < minX) minX = xs[p]
            if (xs[p] > maxX) maxX = xs[p]
            if (ys[p] < minY) minY = ys[p]
            if (ys[p] > maxY) maxY = ys[p]
        }
    }

    private fun cr(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
    }

    fun wrap(i: Int): Int = ((i % n) + n) % n

    /** Fahrtrichtung der Strecke an Punkt i (Bogenmaß). */
    fun heading(i: Int): Float = atan2(-nx[i], ny[i])

    /** Nächster Streckenpunkt in der Nähe von [hint]. */
    fun nearest(x: Float, y: Float, hint: Int, range: Int = 40): Int {
        var best = hint
        var bestD = Float.MAX_VALUE
        for (o in -range..range) {
            val i = wrap(hint + o)
            val dx = xs[i] - x
            val dy = ys[i] - y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun nearestAll(x: Float, y: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val dx = xs[i] - x
            val dy = ys[i] - y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun distTo(x: Float, y: Float, i: Int): Float = hypot(x - xs[i], y - ys[i])
}
