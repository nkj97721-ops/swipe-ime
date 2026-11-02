package ime.swipe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import swipe.trail.SwipeTrail
import kotlin.math.hypot

private enum class Kind { LETTER, BACK, SPACE, ENTER, TEXT, TO_NUM, TO_SYM, TO_LETTERS, COMMA, TOOL, SHIFT }

private class Key(
    val label: String,
    val kind: Kind,
    val value: String,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float
) {
    fun hit(x: Float, y: Float): Boolean = x >= l && x < r && y >= t && y < b

    val rep: Boolean
        get() = kind == Kind.BACK || (kind == Kind.TOOL && (value == "left" || value == "right"))
}

private enum class Panel { LETTERS, NUM, SYM }

/** 8 行 上面 4 行是字母 数字 符号面板 下面 4 行是常驻工具行 最后一行是背景不透明度档位 */
class KeyboardView(
    ctx: Context,
    private val host: KeyboardView.Host,
    private val decoder: GlideDecoder,
    private val store: Store
) : View(ctx) {

    interface Host {
        fun onLetter(c: Char)
        fun onBackspace()
        fun onSpace()
        fun onEnter()
        fun onText(s: String)
        fun onGlide(xs: FloatArray, ys: FloatArray, n: Int)
        fun onTool(name: String)
    }

    private val dp = resources.displayMetrics.density
    private val trail = SwipeTrail(dp, TrailParams.load(store))
    private val uiHandler = Handler(Looper.getMainLooper())

    private val lettersKeys = ArrayList<Key>()
    private val numKeys = ArrayList<Key>()
    private val symKeys = ArrayList<Key>()
    private val toolKeys = ArrayList<Key>()
    private var panel = Panel.LETTERS
    private var numLeftW = 0f
    private var rowH = 0f
    private var gthr = 30f
    private var bgA = store.bgAlpha * 255 / 100
    private var uppercase = false
    private var longPressFired = false

    var activeTool: String? = null
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(143, 176, 255); textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 170, 170); textAlign = Paint.Align.CENTER }
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val sep = Paint().apply { color = Color.rgb(34, 34, 34) }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(58, 58, 58)
        style = Paint.Style.STROKE
    }
    private val path = Path()

    // 触摸状态
    private var downKey: Key? = null
    private var pressedKey: Key? = null
    private var dx = 0f
    private var dy = 0f
    private var glide = false
    private var swipedUp = false
    private var fed = 0
    private var n = 0
    private val px = FloatArray(1024)
    private val py = FloatArray(1024)
    private val pt = LongArray(1024)

    private val repeater = object : Runnable {
        override fun run() {
            val k = downKey ?: return
            doRepeat(k)
            uiHandler.postDelayed(this, 50)
        }
    }

    private fun doRepeat(k: Key) {
        if (k.kind == Kind.BACK) host.onBackspace() else if (k.kind == Kind.TOOL) host.onTool(k.value)
    }

    fun setBgAlpha(a: Int) {
        bgA = a
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        buildKeys(w.toFloat(), h.toFloat())
    }

    private fun buildKeys(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val rh = h / 7f
        val kwF = w / 10f
        rowH = rh
        big.textSize = 0.058f * w
        mid.textSize = 0.042f * w
        accent.textSize = 0.042f * w
        small.textSize = 0.032f * w
        box.strokeWidth = 1.5f * dp
        icon.strokeWidth = 2.2f * dp
        sep.strokeWidth = 1f * dp
        lettersKeys.clear()
        numKeys.clear()
        symKeys.clear()
        toolKeys.clear()

        // 字母面板固定全宽，移除左手/右手单手布局
        val bw = w
        val ox = 0f
        val kwL = bw / 10f
        gthr = 0.55f * kwL
        val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val offs = floatArrayOf(0f, 0.5f * kwL, 1.5f * kwL)
        // 第二行首尾留白作为全文光标跳转热区：点击 a 左侧到全文首，点击 l 右侧到全文尾。
        lettersKeys.add(Key("", Kind.TOOL, "para_start", ox, rh, ox + 0.5f * kwL, 2 * rh))
        lettersKeys.add(Key("", Kind.TOOL, "para_end", ox + 9.5f * kwL, rh, ox + bw, 2 * rh))
        val cx = FloatArray(26)
        val cy = FloatArray(26)
        for (r in 0..2) {
            val row = rows[r]
            for (i in row.indices) {
                val l = ox + offs[r] + i * kwL
                lettersKeys.add(Key(if (uppercase) row[i].uppercaseChar().toString() else row[i].toString(), Kind.LETTER, if (uppercase) row[i].uppercaseChar().toString() else row[i].toString(), l, r * rh, l + kwL, (r + 1) * rh))
                val idx = row[i] - 'a'
                cx[idx] = l + kwL / 2f
                cy[idx] = r * rh + rh / 2f
            }
        }
        decoder.setLayout(cx, cy, kwL)
        lettersKeys.add(Key("", Kind.BACK, "", ox + 8.5f * kwL, 2 * rh, ox + bw, 3 * rh))
        lettersKeys.add(Key("⇧", Kind.SHIFT, "", 0f, 2 * rh, ox + 1.5f * kwL, 3 * rh))
        lettersKeys.add(Key("123", Kind.TO_NUM, "", 0f, 3 * rh, 0.2f * w, 4 * rh))
        lettersKeys.add(Key("，", Kind.COMMA, "", 0.2f * w, 3 * rh, 0.3f * w, 4 * rh))
        lettersKeys.add(Key("", Kind.SPACE, " ", 0.3f * w, 3 * rh, 0.72f * w, 4 * rh))
        lettersKeys.add(Key("符", Kind.TO_SYM, "", 0.72f * w, 3 * rh, 0.84f * w, 4 * rh))
        lettersKeys.add(Key("换行", Kind.ENTER, "", 0.84f * w, 3 * rh, w, 4 * rh))

        // 九键数字面板
        val lw = 0.186f * w
        val mw = 0.2093f * w
        val x1 = lw
        val x2 = lw + mw
        val x3 = lw + 2 * mw
        val x4 = lw + 3 * mw
        numLeftW = lw
        val left = arrayOf("@", "%", "-", "+")
        val sh = 0.726f * rh
        for (i in 0..3) numKeys.add(Key(left[i], Kind.TEXT, left[i], 0f, i * sh, lw, (i + 1) * sh))
        numKeys.add(Key("", Kind.TO_LETTERS, "", 0f, 4 * sh, lw, 4 * rh))
        val digs = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        for (r in 0..2) {
            for (c in 0..2) {
                val d = digs[r * 3 + c]
                numKeys.add(Key(d, Kind.TEXT, d, x1 + c * mw, r * rh, x1 + (c + 1) * mw, (r + 1) * rh))
            }
        }
        numKeys.add(Key("符号", Kind.TO_SYM, "", x1, 3 * rh, x2, 4 * rh))
        // 0 的正下方放小数点；空格键横跨底部中间两列
        numKeys.add(Key("空格", Kind.SPACE, " ", x2, 3 * rh, x4, 4 * rh))
        numKeys.add(Key(".", Kind.TEXT, ".", x4, 2 * rh, w, 3 * rh))
        numKeys.add(Key("", Kind.BACK, "", x4, 0f, w, rh))
        numKeys.add(Key("0", Kind.TEXT, "0", x4, rh, w, 2 * rh))
        numKeys.add(Key("换行", Kind.ENTER, "", x4, 3 * rh, w, 4 * rh))

        // 符号面板
        val r1 = arrayOf("_", "—", ";", "#", "%", "&", "^", "*", "+", "=")
        val v1 = arrayOf("_", "——", ";", "#", "%", "&", "^", "*", "+", "=")
        val r2 = arrayOf("-", "/", ":", "～", "(", ")", "…", "@", "“", "”")
        val v2 = arrayOf("-", "/", ":", "～", "(", ")", "……", "@", "“", "”")
        for (i in 0..9) {
            symKeys.add(Key(r1[i], Kind.TEXT, v1[i], i * kwF, 0f, (i + 1) * kwF, rh))
            symKeys.add(Key(r2[i], Kind.TEXT, v2[i], i * kwF, rh, (i + 1) * kwF, 2 * rh))
        }
        val c0 = 0.1528f * w
        val cw = 0.1157f * w
        symKeys.add(Key("123", Kind.TO_NUM, "", 0f, 2 * rh, c0, 3 * rh))
        val r3 = arrayOf("。", "，", "、", "？", "！", ".")
        for (i in 0..5) symKeys.add(Key(r3[i], Kind.TEXT, r3[i], c0 + i * cw, 2 * rh, c0 + (i + 1) * cw, 3 * rh))
        symKeys.add(Key("", Kind.BACK, "", 0.8472f * w, 2 * rh, w, 3 * rh))
        symKeys.add(Key("", Kind.TO_LETTERS, "", 0.011f * w, 3 * rh, 0.133f * w, 4 * rh))

        // 常驻三行工具键 从上到下
        val t1 = arrayOf("左移", "剪贴板", "常用语", "右移")
        val c1 = arrayOf("left", "clip", "phrase", "right")
        val w4 = w / 4f
        for (i in 0..3) toolKeys.add(Key(t1[i], Kind.TOOL, c1[i], i * w4, 4 * rh, (i + 1) * w4, 5 * rh))
        val t2 = arrayOf("全选并复制", "粘贴并回车")
        val c2 = arrayOf("select_copy", "paste_enter")
        val w2 = w / 2f
        for (i in 0..1) toolKeys.add(Key(t2[i], Kind.TOOL, c2[i], i * w2, 5 * rh, (i + 1) * w2, 6 * rh))
        // 最底部收起热区高度减半，不显示文字，点击仍可收起键盘
        toolKeys.add(Key("", Kind.TOOL, "hide", 0f, 6.375f * rh, w, 6.625f * rh))
        invalidate()
    }

    private fun cur(): List<Key> = when (panel) {
        Panel.LETTERS -> lettersKeys
        Panel.NUM -> numKeys
        Panel.SYM -> symKeys
    }

    private fun findKey(x: Float, y: Float): Key? {
        val list: List<Key> = if (y >= 4f * rowH) toolKeys else cur()
        for (k in list) if (k.hit(x, y)) return k
        return null
    }

    // ---------- 绘制 ----------

    private fun text(c: Canvas, s: String, x: Float, y: Float, p: Paint) {
        c.drawText(s, x, y - (p.ascent() + p.descent()) / 2f, p)
    }

    private fun drawBackspace(c: Canvas, cx: Float, cy: Float) {
        val s = 0.027f * width
        path.rewind()
        path.moveTo(cx - s, cy)
        path.lineTo(cx - 0.35f * s, cy - 0.75f * s)
        path.lineTo(cx + s, cy - 0.75f * s)
        path.lineTo(cx + s, cy + 0.75f * s)
        path.lineTo(cx - 0.35f * s, cy + 0.75f * s)
        path.close()
        c.drawPath(path, icon)
        c.drawLine(cx - 0.05f * s, cy - 0.3f * s, cx + 0.55f * s, cy + 0.3f * s, icon)
        c.drawLine(cx - 0.05f * s, cy + 0.3f * s, cx + 0.55f * s, cy - 0.3f * s, icon)
    }

    private fun drawArrow(c: Canvas, k: Key, withBox: Boolean) {
        if (withBox) {
            val m = 4f * dp
            val vm = 0.12f * (k.b - k.t)
            c.drawRect(k.l + m, k.t + vm, k.r - m, k.b - vm, box)
        }
        val cx = (k.l + k.r) / 2f
        val cy = (k.t + k.b) / 2f
        val s = 0.03f * width
        c.drawLine(cx + s, cy, cx - s, cy, icon)
        c.drawLine(cx - s, cy, cx - 0.3f * s, cy - 0.6f * s, icon)
        c.drawLine(cx - s, cy, cx - 0.3f * s, cy + 0.6f * s, icon)
    }

    private fun drawKey(c: Canvas, k: Key) {
        val cx = (k.l + k.r) / 2f
        val cy = (k.t + k.b) / 2f
        when (k.kind) {
            Kind.BACK -> drawBackspace(c, cx, cy)
            Kind.TO_LETTERS -> drawArrow(c, k, false)
            Kind.COMMA -> {
                val kh = k.b - k.t
                text(c, "，", cx, cy + 0.12f * kh, big)
                text(c, "。", cx, cy - 0.28f * kh, small)
            }
            Kind.LETTER -> text(c, k.label, cx, cy, big)
            Kind.SHIFT -> text(c, k.label, cx, cy, big)
            Kind.TEXT -> text(c, k.label, cx, cy, if (panel == Panel.NUM && k.r <= numLeftW + 1f) mid else big)
            Kind.TOOL -> text(c, k.label, cx, cy, mid)
            else -> if (k.label.isNotEmpty()) text(c, k.label, cx, cy, mid)
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.argb(bgA, 0, 0, 0), PorterDuff.Mode.SRC)
        for (k in cur()) drawKey(c, k)
        c.drawLine(0f, 4f * rowH, width.toFloat(), 4f * rowH, sep)
        for (k in toolKeys) drawKey(c, k)
        if (trail.draw(c, SystemClock.uptimeMillis())) postInvalidateOnAnimation()
    }

    // ---------- 触摸 ----------

    private fun addPt(x: Float, y: Float, t: Long) {
        if (n < px.size) {
            px[n] = x
            py[n] = y
            pt[n] = t
            n++
        }
    }

    private fun fire(k: Key) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (k.kind) {
            Kind.LETTER -> host.onLetter(k.value[0])
            Kind.SHIFT -> { uppercase = !uppercase; buildKeys(width.toFloat(), height.toFloat()) }
            Kind.SPACE -> host.onSpace()
            Kind.ENTER -> host.onEnter()
            Kind.TEXT -> host.onText(k.value)
            Kind.TO_NUM -> panel = Panel.NUM
            Kind.TO_SYM -> panel = Panel.SYM
            Kind.TO_LETTERS -> panel = Panel.LETTERS
            Kind.COMMA -> host.onText(if (swipedUp) "。" else "，")
            Kind.TOOL -> host.onTool(k.value)
            Kind.BACK -> {}
        }
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val k = findKey(e.x, e.y)
                downKey = k
                pressedKey = k
                dx = e.x
                dy = e.y
                glide = false
                swipedUp = false
                fed = 0
                n = 0
                if (k != null && k.kind == Kind.LETTER) addPt(e.x, e.y, e.eventTime)
                longPressFired = false
                if (k != null && k.kind == Kind.BACK) {
                    uiHandler.postDelayed(repeater, 400)
                } else if (k != null && k.rep) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    doRepeat(k)
                    uiHandler.postDelayed(repeater, 400)
                }
                if (k != null && k.kind == Kind.TOOL && (k.value == "clip" || k.value == "phrase")) {
                    uiHandler.postDelayed({
                        if (downKey === k && !glide) {
                            longPressFired = true
                            host.onTool(if (k.value == "clip") "alpha_40" else "alpha_60")
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                    }, 500)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val k = downKey ?: return true
                if (k.kind == Kind.LETTER) {
                    for (i in 0 until e.historySize) {
                        addPt(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
                    }
                    addPt(e.x, e.y, e.eventTime)
                    if (!glide && hypot(e.x - dx, e.y - dy) > gthr) {
                        glide = true
                        pressedKey = null
                        trail.params = TrailParams.load(store)
                        trail.down(px[0], py[0], pt[0])
                        fed = 1
                    }
                    if (glide) {
                        while (fed < n) {
                            trail.move(px[fed], py[fed], pt[fed])
                            fed++
                        }
                    }
                } else if (k.kind == Kind.COMMA) {
                    if (dy - e.y > 0.45f * rowH) swipedUp = true
                } else if (k.kind == Kind.BACK && kotlin.math.abs(e.y - dy) > 0.25f * rowH) {
                    uiHandler.removeCallbacks(repeater)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                uiHandler.removeCallbacks(repeater)
                val k = downKey
                downKey = null
                pressedKey = null
                if (glide) {
                    trail.up(e.eventTime)
                    glide = false
                    host.onGlide(px.copyOf(n), py.copyOf(n), n)
                } else if (k != null && k.kind == Kind.BACK) {
                    val deltaY = e.y - dy
                    if (deltaY < -0.35f * rowH) host.onTool("clear")
                    else if (deltaY > 0.35f * rowH) host.onTool("undo")
                    else host.onBackspace()
                } else if (k != null && !k.rep && !longPressFired) {
                    val upKey = findKey(e.x, e.y)
                    if (upKey === k || hypot(e.x - dx, e.y - dy) < gthr || k.kind == Kind.COMMA) fire(k)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(repeater)
                if (glide) trail.up(e.eventTime)
                glide = false
                longPressFired = false
                downKey = null
                pressedKey = null
                invalidate()
            }
        }
        return true
    }
}
