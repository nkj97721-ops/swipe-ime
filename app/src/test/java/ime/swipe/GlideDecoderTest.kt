package ime.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlideDecoderTest {
    private val kw = 100f
    private val rh = 57f
    private val centers = HashMap<Char, Pair<Float, Float>>()

    private fun decoder(): GlideDecoder {
        val lex = Lexicon().apply {
            loadRows(sequenceOf(
                "bei\t北\t80",
                "beijing\t北京\t500",
                "hao\t好\t100",
                "jing\t京\t80",
                "ni\t你\t200",
                "nihao\t你好\t600",
                "zhang\t张\t300",
                "zhong\t中\t200",
                "zhongguo\t中国\t700"
            ))
        }
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val offs = floatArrayOf(0f, 0.5f * kw, 1.5f * kw)
        val cx = FloatArray(26)
        val cy = FloatArray(26)
        for (r in 0..2) {
            for (i in rows[r].indices) {
                val x = offs[r] + i * kw + kw / 2f
                val y = r * rh + rh / 2f
                cx[rows[r][i] - 'a'] = x
                cy[rows[r][i] - 'a'] = y
                centers[rows[r][i]] = Pair(x, y)
            }
        }
        return GlideDecoder(lex).apply { setLayout(cx, cy, kw) }
    }

    /** 沿按键中心依次连线 每段 10 个点 */
    private fun path(word: String): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (i in 0 until word.length - 1) {
            val a = centers[word[i]]!!
            val b = centers[word[i + 1]]!!
            for (s in 0 until 10) {
                val t = s / 10f
                xs.add(a.first + (b.first - a.first) * t)
                ys.add(a.second + (b.second - a.second) * t)
            }
        }
        val last = centers[word.last()]!!
        xs.add(last.first)
        ys.add(last.second)
        return Pair(xs.toFloatArray(), ys.toFloatArray())
    }

    @Test fun idealPathDecodesToItsWord() {
        val d = decoder()
        val (xs, ys) = path("nihao")
        val hits = d.decode(xs, ys, xs.size, 8)
        assertEquals("nihao", hits.first().py)
    }

    @Test fun shortSlideIsTreatedAsTap() {
        val d = decoder()
        val n = centers['n']!!
        val xs = floatArrayOf(n.first, n.first + 30f)
        val ys = floatArrayOf(n.second, n.second)
        assertEquals('n', d.tapFallback(xs, ys, 2))
    }

    @Test fun longPathIsNotATap() {
        val d = decoder()
        val (xs, ys) = path("nihao")
        assertNull(d.tapFallback(xs, ys, xs.size))
    }

    @Test fun pathAtDecodeFloorStillDecodesOrFallsBack() {
        val d = decoder()
        val (xs, ys) = path("ni")
        // 两个字母相距超过 0.8 个键宽 不应被当成点按
        assertNull(d.tapFallback(xs, ys, xs.size))
        assertNotNull(d.decode(xs, ys, xs.size, 8))
    }
}
