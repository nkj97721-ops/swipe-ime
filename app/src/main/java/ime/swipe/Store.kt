package ime.swipe

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class Store(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("ime", Context.MODE_PRIVATE)

    fun prefs(): SharedPreferences = sp

    fun getList(key: String, def: List<String>): MutableList<String> {
        val s = sp.getString(key, null) ?: return def.toMutableList()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { a.getString(it) }
        } catch (e: Exception) {
            def.toMutableList()
        }
    }

    fun getFloat(key: String, def: Float): Float = sp.getFloat(key, def)

    fun putFloat(key: String, v: Float) {
        sp.edit().putFloat(key, v).apply()
    }

    fun putList(key: String, l: List<String>) {
        sp.edit().putString(key, JSONArray(l).toString()).apply()
    }

    /** 本地上下文词频：只在非敏感输入框里由用户主动选词时更新。 */
    fun contextScores(previous: String, nextWords: List<String>): Map<String, Int> {
        if (previous.isBlank() || nextWords.isEmpty()) return emptyMap()
        return try {
            val data = JSONObject(sp.getString("context_pairs", "{}") ?: "{}")
            nextWords.associateWith { data.optInt("$previous>$it", 0) }
        } catch (_: Exception) { emptyMap() }
    }

    fun learnContext(previous: String, next: String) {
        if (previous.isBlank() || next.isBlank()) return
        try {
            val data = JSONObject(sp.getString("context_pairs", "{}") ?: "{}")
            val key = "$previous>$next"
            data.put(key, (data.optInt(key, 0) + 1).coerceAtMost(10000))
            while (data.length() > 512) {
                var weakestKey: String? = null
                var weakestValue = Int.MAX_VALUE
                val keys = data.keys()
                while (keys.hasNext()) {
                    val candidate = keys.next()
                    val value = data.optInt(candidate, 0)
                    if (value < weakestValue) {
                        weakestValue = value
                        weakestKey = candidate
                    }
                }
                if (weakestKey == null) break
                data.remove(weakestKey)
            }
            sp.edit().putString("context_pairs", data.toString()).apply()
        } catch (_: Exception) { }
    }

    fun getClipEntries(): MutableList<ClipEntry> {
        val raw = sp.getString("clip_entries", null)
        if (raw != null) {
            return try {
                val array = JSONArray(raw)
                MutableList(array.length()) { ClipEntry.fromJson(array.getJSONObject(it)) }
            } catch (_: Exception) {
                mutableListOf()
            }
        }
        // Migrate existing text-only clipboard history without losing old entries.
        return getList("clips", emptyList()).map { ClipEntry(text = it) }.toMutableList()
    }

    fun putClipEntries(entries: List<ClipEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        sp.edit().putString("clip_entries", array.toString()).apply()
    }

    /** 键盘黑色背景不透明度 0 到 100 */
    var bgAlpha: Int
        get() = sp.getInt("bg_alpha", 80)
        set(v) {
            sp.edit().putInt("bg_alpha", v).apply()
        }

}
