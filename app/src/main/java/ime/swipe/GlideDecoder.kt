package ime.swipe

import kotlin.math.hypot
import kotlin.math.ln

/** 滑行解码 把划动路径与词库拼音串做形状匹配 */
class GlideDecoder(private val lex: Lexicon) {
    class Hit(val py: String, val score: Float)

    private var cx = FloatArray(26)
    private var cy = FloatArray(26)
    private var kw = 100f

    private val np = 32
    private val sx = FloatArray(np)
    private val sy = FloatArray(np)
    private val ux = FloatArray(np)
    private val uy = FloatArray(np)
    private val ix = FloatArray(64)
    private val iy = FloatArray(64)

    fun setLayout(x: FloatArray, y: FloatArray, keyWidth: Float) {
        cx = x.copyOf()
        cy = y.copyOf()
        kw = keyWidth
    }

    /** 重采样为 np 个等弧长点 返回总长度 */
    private fun resample(xs: FloatArray, ys: FloatArray, n: Int, ox: FloatArray, oy: FloatArray): Float {
        var total = 0f
        for (i in 1 until n) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (n < 2 || total < 0.001f) {
            for (k in 0 until np) {
                ox[k] = xs[0]
                oy[k] = ys[0]
            }
            return 0f
        }
        var seg = 1
        var acc = 0f
        var segLen = hypot(xs[1] - xs[0], ys[1] - ys[0])
        for (k in 0 until np) {
            val target = total * k / (np - 1)
            while (seg < n - 1 && acc + segLen < target) {
                acc += segLen
                seg++
                segLen = hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1])
            }
            val t = if (segLen > 0.000001f) ((target - acc) / segLen).coerceIn(0f, 1f) else 0f
            ox[k] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t
            oy[k] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t
        }
        return total
    }

    /** 距离 (x y) 在 1.5 个键宽以内的字母 一个都没有则取最近的 */
    private fun near(x: Float, y: Float): BooleanArray {
        val r = BooleanArray(26)
        var best = 0
        var bd = Float.MAX_VALUE
        var any = false
        for (k in 0 until 26) {
            val d = hypot(cx[k] - x, cy[k] - y)
            if (d < bd) {
                bd = d
                best = k
            }
            if (d < 1.5f * kw) {
                r[k] = true
                any = true
            }
        }
        if (!any) r[best] = true
        return r
    }

    fun decode(xs: FloatArray, ys: FloatArray, n: Int, top: Int): List<Hit> {
        if (!lex.ready || n < 2) return emptyList()
        val lin = resample(xs, ys, n, sx, sy)
        if (lin < kw * 0.8f) return emptyList()
        val starts = near(xs[0], ys[0])
        val ends = near(xs[n - 1], ys[n - 1])
        val maxLn = ln(1f + lex.maxW.toFloat())
        val best = ArrayList<Hit>()

        for (s in 0 until 26) {
            if (!starts[s]) continue
            for (idx in lex.bucket(s)) {
                val w = lex.upy[idx]
                val len = w.length
                if (len < 2 || len > 24) continue
                if (!ends[w[len - 1] - 'a']) continue

                var m = 0
                var prev = -1
                for (ch in w) {
                    val k = ch - 'a'
                    if (k == prev) continue
                    prev = k
                    ix[m] = cx[k]
                    iy[m] = cy[k]
                    m++
                }
                if (m < 2) continue
                val lid = resample(ix, iy, m, ux, uy)
                if (lin < 0.55f * lid - kw || lin > 1.8f * lid + kw) continue

                var d = 0f
                for (k in 0 until np) d += hypot(sx[k] - ux[k], sy[k] - uy[k])
                d /= (np * kw)
                if (d > 1.5f) continue
                val prior = ln(1f + lex.uw[idx].toFloat()) / maxLn
                val score = d + 0.7f * (1f - prior)
                if (best.size >= top && score >= best[best.size - 1].score) continue

                var pos = best.size
                while (pos > 0 && best[pos - 1].score > score) pos--
                best.add(pos, Hit(w, score))
                if (best.size > top) best.removeAt(best.size - 1)
            }
        }
        return best
    }
}
