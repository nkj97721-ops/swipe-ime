package ime.swipe

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import java.io.File
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 剪贴板与常用语面板 盖在键盘上半部分 */
class PanelView(ctx: Context, private val cb: PanelView.Callback) : LinearLayout(ctx) {
    interface Callback {
        fun onPanelPick(text: String)
        fun onPanelPickClip(entry: ClipEntry)
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

    private fun showFullText(value: String) {
        val content = TextView(context)
        content.text = value
        content.setTextColor(Color.WHITE)
        content.textSize = 16f
        content.setTextIsSelectable(true)
        content.setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())

        val scroll = ScrollView(context)
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(context)
            .setTitle("完整内容")
            .setView(scroll)
            .setPositiveButton("粘贴") { _, _ -> cb.onPanelPick(value) }
            .setNegativeButton("关闭", null)
            .show()
    }

    fun setBgAlpha(a: Int) {
        setBackgroundColor(Color.argb(a, 0, 0, 0))
    }

    fun showClipboard(items: List<ClipEntry>) {
        kind = 1
        title.text = "剪贴板"
        action.text = "清空"
        list.removeAllViews()
        if (items.isEmpty()) {
            showEmpty("还没有复制过内容")
            return
        }
        for (i in items.indices) {
            val entry = items[i]
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            if (entry.fileName != null && entry.mimeType?.startsWith("image/") == true) {
                val image = ImageView(context)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                val file = File(context.filesDir, "clipboard/${entry.fileName}")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 96)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bitmap != null) image.setImageBitmap(bitmap)
                row.addView(image, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply {
                    leftMargin = (12 * d).toInt()
                    rightMargin = (4 * d).toInt()
                })
            }

            val tv = TextView(context)
            tv.text = entry.preview
            tv.setTextColor(Color.WHITE)
            tv.textSize = 16f
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setPadding((18 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
            tv.setOnClickListener { cb.onPanelPickClip(entry) }
            tv.setOnLongClickListener {
                cb.onPanelDelete(i)
                true
            }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val detail = TextView(context)
            detail.text = "查看"
            detail.setTextColor(Color.rgb(143, 176, 255))
            detail.textSize = 14f
            detail.gravity = Gravity.CENTER
            detail.setPadding((12 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
            detail.setOnClickListener { showFullEntry(entry) }
            row.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addDivider()
        }
    }

    private fun showFullEntry(entry: ClipEntry) {
        if (entry.fileName != null) {
            val content = LinearLayout(context)
            content.orientation = LinearLayout.VERTICAL
            content.setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
            val file = File(context.filesDir, "clipboard/${entry.fileName}")
            if (entry.mimeType?.startsWith("image/") == true) {
                val image = ImageView(context)
                image.adjustViewBounds = true
                image.scaleType = ImageView.ScaleType.FIT_CENTER
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 900)
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.let {
                    image.setImageBitmap(it)
                }
                content.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (280 * d).toInt()))
            }
            val info = TextView(context)
            info.text = "${entry.label.ifBlank { file.name }}\n${entry.mimeType ?: "未知类型"}\n${file.length()} bytes"
            info.setTextColor(Color.WHITE)
            info.textSize = 14f
            content.addView(info)
            AlertDialog.Builder(context)
                .setTitle(if (entry.mimeType?.startsWith("image/") == true) "图片预览" else "文件信息")
                .setView(content)
                .setPositiveButton("粘贴") { _, _ -> cb.onPanelPickClip(entry) }
                .setNegativeButton("关闭", null)
                .show()
            return
        }

        val content = TextView(context)
        content.text = if (!entry.html.isNullOrEmpty()) {
            android.text.Html.fromHtml(entry.html, android.text.Html.FROM_HTML_MODE_LEGACY)
        } else entry.text.orEmpty()
        content.setTextColor(Color.WHITE)
        content.textSize = 16f
        content.setTextIsSelectable(true)
        content.setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        val scroll = ScrollView(context)
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(context)
            .setTitle("完整内容")
            .setView(scroll)
            .setPositiveButton("粘贴") { _, _ -> cb.onPanelPickClip(entry) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showEmpty(message: String) {
        val empty = TextView(context)
        empty.text = message
        empty.setTextColor(Color.rgb(120, 120, 120))
        empty.textSize = 15f
        empty.gravity = Gravity.CENTER
        empty.setPadding(0, (30 * d).toInt(), 0, 0)
        list.addView(empty, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun addDivider() {
        val line = View(context)
        line.setBackgroundColor(Color.rgb(34, 34, 34))
        list.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
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
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL

            val value = items[i]
            val tv = TextView(context)
            tv.text = value
            tv.setTextColor(Color.WHITE)
            tv.textSize = 17f
            tv.maxLines = 2
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.setPadding((18 * d).toInt(), (12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
            tv.setOnClickListener { cb.onPanelPick(value) }
            tv.setOnLongClickListener {
                cb.onPanelDelete(i)
                true
            }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val detail = TextView(context)
            detail.text = "查看"
            detail.setTextColor(Color.rgb(143, 176, 255))
            detail.textSize = 14f
            detail.gravity = Gravity.CENTER
            detail.setPadding((12 * d).toInt(), (12 * d).toInt(), (18 * d).toInt(), (12 * d).toInt())
            detail.setOnClickListener { showFullText(value) }
            row.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val line = View(context)
            line.setBackgroundColor(Color.rgb(34, 34, 34))
            list.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
    }
}
