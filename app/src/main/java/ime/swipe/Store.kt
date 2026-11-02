package ime.swipe

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

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

    /** 键盘黑色背景不透明度 0 到 100 */
    var bgAlpha: Int
        get() = sp.getInt("bg_alpha", 80)
        set(v) {
            sp.edit().putInt("bg_alpha", v).apply()
        }

    /** 单手模式 0 关 1 左手 2 右手 */
    var hand: Int
        get() = sp.getInt("hand", 0)
        set(v) {
            sp.edit().putInt("hand", v).apply()
        }
}
