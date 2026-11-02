package ime.swipe

import android.content.Context
import kotlin.math.ln

class Cand(val text: String, val used: Int)

/** 词库 assets/lex.txt 每行: 拼音(无空格) TAB 词 TAB 权重 已按 拼音升序 权重降序 排好 */
class Lexicon {
    @Volatile
    var ready = false

    private var py: Array<String> = emptyArray()
    private var wd: Array<String> = emptyArray()
    private var wt: IntArray = IntArray(0)

    /** 去重后的拼音串 供滑行匹配 */
    var upy: Array<String> = emptyArray()
    var uw: IntArray = IntArray(0)
    var maxW: Int = 1
    private val bStart = IntArray(27)

    fun load(ctx: Context) {
        val pl = ArrayList<String>(220000)
        val wl = ArrayList<String>(220000)
        val tl = ArrayList<Int>(220000)
        ctx.assets.open("lex.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val a = line.indexOf('\t')
                if (a <= 0) continue
                val b = line.indexOf('\t', a + 1)
                if (b < 0) continue
                pl.add(line.substring(0, a))
                wl.add(line.substring(a + 1, b))
                tl.add(line.substring(b + 1).toIntOrNull() ?: 1)
            }
        }
        py = pl.toTypedArray()
        wd = wl.toTypedArray()
        wt = tl.toIntArray()

        val up = ArrayList<String>()
        val uwl = ArrayList<Int>()
        var prev = ""
        var mx = 1
        for (k in py.indices) {
            if (py[k] != prev) {
                up.add(py[k])
                uwl.add(wt[k])
                prev = py[k]
            }
            if (wt[k] > mx) mx = wt[k]
        }
        upy = up.toTypedArray()
        uw = uwl.toIntArray()
        maxW = mx

        var i = 0
        for (f in 0 until 26) {
            bStart[f] = i
            while (i < upy.size && (upy[i][0] - 'a') == f) i++
        }
        bStart[26] = upy.size
        ready = true
    }

    fun bucket(s: Int): IntRange = bStart[s] until bStart[s + 1]

    /** 第一个 py >= s 的下标 */
    private fun lower(s: String): Int {
        var lo = 0
        var hi = py.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (py[m] < s) lo = m + 1 else hi = m
        }
        return lo
    }

    private fun score(i: Int, len: Int): Float {
        return ln(1f + wt[i].toFloat()) + (if (py[i].length == len) 2f else 0f)
    }

    /** 敲字候选 整串匹配与补全混排 之后是只消耗前缀的分段候选 */
    fun candidates(s: String): List<Cand> {
        if (!ready || s.isEmpty()) return emptyList()
        val out = ArrayList<Cand>()
        val seen = HashSet<String>()

        val idxs = ArrayList<Int>()
        var i = lower(s)
        while (i < py.size && idxs.size < 300 && py[i].startsWith(s)) {
            idxs.add(i)
            i++
        }
        val sorted = idxs.sortedByDescending { score(it, s.length) }
        for (k in sorted) {
            if (out.size >= 24) break
            if (seen.add(wd[k] + "#" + s.length)) out.add(Cand(wd[k], s.length))
        }

        var len = s.length - 1
        while (len >= 1 && out.size < 40) {
            val p = s.substring(0, len)
            var j = lower(p)
            var c = 0
            while (j < py.size && py[j] == p && c < 3) {
                if (seen.add(wd[j] + "#" + len)) out.add(Cand(wd[j], len))
                j++
                c++
            }
            len--
        }
        return out
    }

    /** 某个完整拼音对应的词 按权重取前 n 个 */
    fun wordsFor(p: String, n: Int): List<String> {
        val out = ArrayList<String>()
        var j = lower(p)
        while (j < py.size && py[j] == p && out.size < n) {
            out.add(wd[j])
            j++
        }
        return out
    }
}
