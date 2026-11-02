package ime.swipe

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 剪贴板与常用语面板 盖在键盘上半部分 */
class PanelView(ctx: Context, private val cb: PanelView.Callback) : LinearLayout(ctx) {
    interface Callback {
        fun onPanelPick(text: String)
        fun onPanelBack()
        fun onPanelAction(kind: Int)
        fun onPanelDelete(index: Int)
    }

    private val d = resources.displayMetrics.density
    private val title = TextView(ctx)
    private val action = TextView(ctx)
    private val list = LinearLayout(ctx)
    private var kind = 0

    init {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)

        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val back = TextView(ctx)
        back.text = "返回"
        back.setTextColor(Color.WHITE)
        back.textSize = 16f
        back.gravity = Gravity.CENTER
        back.setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
        back.setOnClickListener { cb.onPanelBack() }

        title.setTextColor(Color.rgb(170, 170, 170))
        title.textSize = 14f
        title.gravity = Gravity.CENTER

        action.setTextColor(Color.rgb(143, 176, 255))
        action.textSize = 16f
        action.gravity = Gravity.CENTER
        action.setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
        action.setOnClickListener { cb.onPanelAction(kind) }

        header.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (57 * d).toInt()))

        list.orientation = LinearLayout.VERTICAL
        val sv = ScrollView(ctx)
        sv.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setBgAlpha(a: Int) {
        setBackgroundColor(Color.argb(a, 0, 0, 0))
    }

    fun show(kind: Int, items: List<String>) {
        this.kind = kind
        title.text = if (kind == 1) "剪贴板" else "常用语"
        action.text = if (kind == 1) "清空" else "添加当前内容"
        list.removeAllViews()
        if (items.isEmpty()) {
            val empty = TextView(context)
            empty.text = if (kind == 1) "还没有复制过内容" else "还没有常用语"
            empty.setTextColor(Color.rgb(120, 120, 120))
            empty.textSize = 15f
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, (30 * d).toInt(), 0, 0)
            list.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        for (i in items.indices) {
            val tv = TextView(context)
            tv.text = items[i]
            tv.setTextColor(Color.WHITE)
            tv.textSize = 17f
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setPadding((18 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
            tv.setOnClickListener { cb.onPanelPick(items[i]) }
            tv.setOnLongClickListener {
                cb.onPanelDelete(i)
                true
            }
            list.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val line = View(context)
            line.setBackgroundColor(Color.rgb(34, 34, 34))
            list.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
    }
}
