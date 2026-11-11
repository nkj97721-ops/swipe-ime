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

    private fun score(index: Int, inputLength: Int): Float =
        ln(1f + wt[index].toFloat()) + if (py[index].length == inputLength) 2f else 0f

    private data class Match(val word: String, val used: Int, val score: Float)

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
    fun candidates(raw: String): List<Cand> {
        val input = raw.lowercase()
        if (!ready || input.isEmpty()) return emptyList()
        val matches = LinkedHashMap<String, Match>()

        fun addPrefix(prefix: String, boost: Float, cap: Int) {
            var index = lower(prefix)
            var count = 0
            while (index < py.size && count < cap && py[index].startsWith(prefix)) {
                val candidate = Match(wd[index], input.length, score(index, input.length) + boost)
                val old = matches[candidate.word]
                if (old == null || candidate.score > old.score) matches[candidate.word] = candidate
                index++
                count++
            }
        }

        addPrefix(input, 2.5f, 300)
        for (variant in fuzzyVariants(input)) addPrefix(variant, 0.35f, 60)

        if (input.length >= 2) {
            var keyIndex = initialKeys.binarySearch(input).let { if (it >= 0) it else -it - 1 }
            var keyCount = 0
            while (keyIndex < initialKeys.size && keyCount < 80 && initialKeys[keyIndex].startsWith(input)) {
                val key = initialKeys[keyIndex++]
                keyCount++
                for (index in initials[key].orEmpty().take(12)) {
                    val candidate = Match(wd[lower(upy[index])], input.length, ln(1f + uw[index]) - 1f)
                    val old = matches[candidate.word]
                    if (old == null || candidate.score > old.score) matches[candidate.word] = candidate
                }
            }
        }

        val out = matches.values.sortedByDescending { it.score }.take(32)
            .map { Cand(it.word, it.used) }.toMutableList()
        val seen = out.mapTo(HashSet()) { it.text }
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
        return out
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
