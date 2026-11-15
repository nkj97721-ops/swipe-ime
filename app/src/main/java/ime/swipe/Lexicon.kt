package ime.swipe

import android.content.Context
import kotlin.math.ln

class Cand(val text: String, val used: Int)

/** 词库 assets/lex.txt 每行: 拼音 TAB 词 TAB 权重。 */
class Lexicon {
    @Volatile var ready = false
        private set
    private var py: Array<String> = emptyArray()
    private var wd: Array<String> = emptyArray()
    private var wt: IntArray = IntArray(0)
    private var lnw: FloatArray = FloatArray(0)
    var upy: Array<String> = emptyArray()
        private set
    var uw: IntArray = IntArray(0)
        private set
    var maxW: Int = 1
        private set
    private val bStart = IntArray(27)
    private var initials: Map<String, IntArray> = emptyMap()
    private var initialKeys: Array<String> = emptyArray()
    private var syllablesByFirst: Map<Char, List<String>> = emptyMap()

    fun load(ctx: Context) {
        ctx.assets.open("lex.txt").bufferedReader(Charsets.UTF_8).useLines { loadRows(it) }
    }

    internal fun loadRows(lines: Sequence<String>) {
        ready = false
        val rows = ArrayList<Triple<String, String, Int>>()
        for (line in lines) {
            val a = line.indexOf('\t')
            if (a <= 0) continue
            val b = line.indexOf('\t', a + 1)
            if (b < 0) continue
            val code = line.substring(0, a).lowercase()
            val word = line.substring(a + 1, b)
            val weight = line.substring(b + 1).toIntOrNull() ?: 1
            if (code.isEmpty() || word.isEmpty()) continue
            rows.add(Triple(code, word, weight.coerceAtLeast(1)))
        }
        rows.sortWith(compareBy<Triple<String, String, Int>> { it.first }.thenByDescending { it.third })
        py = rows.map { it.first }.toTypedArray()
        wd = rows.map { it.second }.toTypedArray()
        wt = rows.map { it.third }.toIntArray()
        lnw = FloatArray(wt.size) { ln(1f + wt[it].toFloat()) }

        val unique = ArrayList<String>()
        val uniqueWeights = ArrayList<Int>()
        var previous = ""
        var maximum = 1
        for (i in py.indices) {
            if (py[i] != previous) {
                unique.add(py[i])
                uniqueWeights.add(wt[i])
                previous = py[i]
            }
            if (wt[i] > maximum) maximum = wt[i]
        }
        upy = unique.toTypedArray()
        uw = uniqueWeights.toIntArray()
        maxW = maximum

        var cursor = 0
        for (first in 0 until 26) {
            bStart[first] = cursor
            while (cursor < upy.size && upy[cursor].isNotEmpty() && upy[cursor][0] - 'a' == first) cursor++
        }
        bStart[26] = upy.size

        val syllables = rows.asSequence()
            .filter { it.second.length == 1 && it.first.length in 1..6 }
            .map { it.first }.distinct().toList()
        syllablesByFirst = syllables.groupBy { it.first() }
            .mapValues { (_, values) -> values.sortedByDescending { it.length } }

        val initialMap = HashMap<String, MutableList<Int>>()
        for (i in upy.indices) {
            for (key in segmentInitials(upy[i])) initialMap.getOrPut(key) { ArrayList() }.add(i)
        }
        initials = initialMap.mapValues { (_, values) -> values.toIntArray() }
        initialKeys = initials.keys.sorted().toTypedArray()
        ready = true
    }

    private fun segmentInitials(code: String): Set<String> {
        val result = LinkedHashSet<String>()
        val path = StringBuilder()
        fun visit(offset: Int) {
            if (result.size >= 12) return
            if (offset == code.length) {
                if (path.isNotEmpty()) result.add(path.toString())
                return
            }
            for (syllable in syllablesByFirst[code[offset]].orEmpty()) {
                if (!code.startsWith(syllable, offset)) continue
                path.append(syllable[0])
                visit(offset + syllable.length)
                path.setLength(path.length - 1)
                if (result.size >= 12) return
            }
        }
        visit(0)
        return result
    }

    fun bucket(s: Int): IntRange =
        if (s in 0..25) bStart[s] until bStart[s + 1] else IntRange.EMPTY

    private fun lower(value: String): Int {
        var lo = 0
        var hi = py.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (py[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 候选得分：词频的对数，拼音长度正好等于输入的加分，
     * 比输入多出的字母每个扣一点，避免很长的词组仅凭词频压过常用字。
     */
    private fun score(index: Int, inputLength: Int): Float {
        val extra = maxOf(0, py[index].length - inputLength)
        return lnw[index] + (if (py[index].length == inputLength) 2f else 0f) - 0.2f * extra
    }

    private data class Match(val word: String, val used: Int, val score: Float)

    private class Scored(val index: Int, val score: Float)

    private val fuzzyRules = listOf(
        "zh" to "z", "z" to "zh", "ch" to "c", "c" to "ch",
        "sh" to "s", "s" to "sh", "n" to "l", "l" to "n",
        "eng" to "en", "en" to "eng", "ing" to "in", "in" to "ing",
        "ang" to "an", "an" to "ang", "ong" to "on", "on" to "ong"
    )

    private fun fuzzyVariants(input: String): List<String> {
        val out = LinkedHashSet<String>()
        for ((from, to) in fuzzyRules) {
            var at = input.indexOf(from)
            while (at >= 0) {
                val protectedDigraph = from.length == 1 && (from == "z" || from == "c" || from == "s") &&
                    at + 1 < input.length && input[at + 1] == "h"[0]
                if (!protectedDigraph) out.add(input.substring(0, at) + to + input.substring(at + from.length))
                at = input.indexOf(from, at + 1)
                if (out.size >= 48) return out.toList()
            }
        }
        out.remove(input)
        return out.toList()
    }

    /** 精确拼音优先，其次模糊拼音，再次首字母简拼。 */
    private val emergencyWords = mapOf(
        "a" to listOf("啊", "阿", "爱", "安", "按", "俺"),
        "ai" to listOf("爱", "哎", "唉", "矮", "挨", "癌"),
        "an" to listOf("安", "按", "案", "俺", "暗"),
        "ba" to listOf("把", "吧", "八", "爸", "巴"),
        "bu" to listOf("不", "部", "步", "布", "补"),
        "chi" to listOf("吃", "迟", "持", "池"),
        "da" to listOf("大", "打", "达", "答"),
        "de" to listOf("的", "得", "德"),
        "di" to listOf("地", "第", "低", "滴"),
        "hao" to listOf("好", "号", "豪", "耗"),
        "he" to listOf("和", "喝", "何", "合"),
        "ji" to listOf("几", "及", "机", "记", "既"),
        "jian" to listOf("见", "间", "件", "建", "简"),
        "jin" to listOf("今", "进", "近", "金"),
        "ke" to listOf("可", "课", "客", "科"),
        "lai" to listOf("来", "莱", "赖"),
        "le" to listOf("了", "乐"),
        "ma" to listOf("吗", "妈", "马", "嘛"),
        "mei" to listOf("没", "每", "美", "妹"),
        "ni" to listOf("你", "呢", "泥"),
        "hao" to listOf("好", "号", "豪", "耗"),
        "shi" to listOf("是", "时", "事", "使", "市"),
        "wo" to listOf("我", "握", "窝"),
        "xi" to listOf("系", "西", "洗", "喜"),
        "xie" to listOf("写", "些", "谢", "鞋"),
        "yao" to listOf("要", "也", "药", "摇"),
        "yi" to listOf("一", "以", "已", "意", "衣"),
        "you" to listOf("有", "又", "由", "友"),
        "zhong" to listOf("中", "种", "重", "众"),
        "zai" to listOf("在", "再", "载"),
        "zhe" to listOf("这", "着", "者")
    )

    private fun fallbackCandidates(input: String): List<Cand> =
        emergencyWords.entries.asSequence()
            .filter { it.key.startsWith(input) || input.startsWith(it.key) }
            .flatMap { (code, words) -> words.asSequence().map { Cand(it, minOf(code.length, input.length)) } }
            .distinctBy { it.text }
            .take(20)
            .toList()

    fun candidates(raw: String): List<Cand> {
        val input = raw.lowercase()
        if (input.isEmpty()) return emptyList()
        if (!ready) return fallbackCandidates(input)
        val matches = LinkedHashMap<String, Match>()
        val fuzzyMatches = LinkedHashMap<String, Match>()

        // 词库按拼音字母序排列，前缀区间里靠前的不一定是常用词（输入 w 时区间开头全是 wa..），
        // 所以必须扫描整个前缀区间，只保留得分最高的 cap 条，而不是取区间开头的 cap 条。
        fun addPrefix(prefix: String, boost: Float, cap: Int, into: MutableMap<String, Match>) {
            val heap = java.util.PriorityQueue<Scored>(cap + 1) { a, b -> a.score.compareTo(b.score) }
            var index = lower(prefix)
            while (index < py.size && py[index].startsWith(prefix)) {
                val s = score(index, input.length) + boost
                if (heap.size < cap) {
                    heap.add(Scored(index, s))
                } else if (s > heap.peek()!!.score) {
                    heap.poll()
                    heap.add(Scored(index, s))
                }
                index++
            }
            for (entry in heap) {
                val word = wd[entry.index]
                val old = into[word]
                if (old == null || entry.score > old.score) into[word] = Match(word, input.length, entry.score)
            }
        }

        addPrefix(input, 2.5f, 60, matches)
        for (variant in fuzzyVariants(input)) addPrefix(variant, 0.35f, 12, fuzzyMatches)

        if (input.length >= 2) {
            var keyIndex = initialKeys.binarySearch(input).let { if (it >= 0) it else -it - 1 }
            var keyCount = 0
            while (keyIndex < initialKeys.size && keyCount < 80 && initialKeys[keyIndex].startsWith(input)) {
                val key = initialKeys[keyIndex++]
                keyCount++
                for (index in (initials[key] ?: IntArray(0)).take(12)) {
                    val candidate = Match(wd[lower(upy[index])], input.length, ln(1f + uw[index]) - 1f)
                    val old = matches[candidate.word]
                    if (old == null || candidate.score > old.score) matches[candidate.word] = candidate
                }
            }
        }

        val out = matches.values.sortedByDescending { it.score }.take(32)
            .map { Cand(it.word, it.used) }.toMutableList()
        val seen = out.mapTo(HashSet()) { it.text }
        // 模糊拼音只排在精确结果之后，否则输入 ni 会把 里、力 这些 li 的高频字插到 尼、泥 前面
        for (match in fuzzyMatches.values.sortedByDescending { it.score }) {
            if (out.size >= 40) break
            if (seen.add(match.word)) out.add(Cand(match.word, match.used))
        }
        var length = input.length - 1
        while (length >= 1 && out.size < 40) {
            val prefix = input.substring(0, length)
            var index = lower(prefix)
            var count = 0
            while (index < py.size && py[index] == prefix && count < 3) {
                if (seen.add(wd[index])) out.add(Cand(wd[index], length))
                index++
                count++
            }
            length--
        }
        return if (out.isNotEmpty()) out else fallbackCandidates(input)
    }

    fun wordsFor(p: String, n: Int): List<String> {
        if (!ready || n <= 0) return emptyList()
        val out = ArrayList<String>()
        val code = p.lowercase()
        var index = lower(code)
        while (index < py.size && py[index] == code && out.size < n) {
            out.add(wd[index])
            index++
        }
        return out
    }
}
