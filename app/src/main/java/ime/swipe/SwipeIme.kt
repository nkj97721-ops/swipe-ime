package ime.swipe

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class SwipeIme : InputMethodService(), KeyboardView.Host, PanelView.Callback {
    private val lex = Lexicon()
    private val decoder = GlideDecoder(lex)
    private lateinit var store: Store
    private lateinit var kbd: KeyboardView
    private lateinit var panel: PanelView
    private lateinit var candRow: LinearLayout
    private lateinit var candScroll: CandidateScrollView
    private lateinit var candPages: LinearLayout
    private val candidatePageScrolls = ArrayList<ScrollView>()
    private var candidatesExpanded = false

    private val composing = StringBuilder()
    private var mode = 0 // 0 空闲 1 拼音输入 2 滑行结果
    private var cands: List<Cand> = emptyList()
    private var glideWords: List<String> = emptyList()

    private var clips: MutableList<String> = mutableListOf()
    private var phrases: MutableList<String> = mutableListOf()
    private var panelKind = 0 // 0 无 1 剪贴板 2 常用语

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "bg_alpha") applyAlpha()
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { grabClip() }

    companion object {
        private val DEFAULT_PHRASES = listOf("好的", "谢谢", "稍等一下", "收到", "我马上到", "现在不方便 稍后联系你", "你好 请问有什么事")
    }

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        clips = store.getList("clips", emptyList())
        phrases = store.getList("phrases", DEFAULT_PHRASES)
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        store.prefs().registerOnSharedPreferenceChangeListener(prefListener)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        Thread { lex.load(applicationContext) }.start()
    }

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        val rowPx = (57 * d).toInt()
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        candScroll = CandidateScrollView(
            this,
            onVerticalSwipe = { up -> setCandidatesExpanded(up) },
            onLongPressScroll = { dy ->
                val page = (candScroll.scrollX / maxOf(1, resources.displayMetrics.widthPixels))
                    .coerceIn(0, maxOf(0, candidatePageScrolls.lastIndex))
                candidatePageScrolls.getOrNull(page)?.scrollBy(0, dy)
            }
        )
        candScroll.isHorizontalScrollBarEnabled = false
        candPages = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        candRow = candPages
        candScroll.addView(
            candPages,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(
            candScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt())
        )

        val frame = FrameLayout(this)
        kbd = KeyboardView(this, this, decoder, store)
        frame.addView(kbd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowPx * 6))
        panel = PanelView(this, this)
        panel.visibility = View.GONE
        frame.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowPx * 4, Gravity.TOP))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowPx * 6))

        applyAlpha()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        mode = 0
        if (::candRow.isInitialized) clearCands()
        if (::panel.isInitialized) hidePanel()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        currentInputConnection?.finishComposingText()
        composing.setLength(0)
        mode = 0
    }

    // ---------- 背景不透明度 ----------

    private fun applyAlpha() {
        if (!::kbd.isInitialized) return
        val a = store.bgAlpha * 255 / 100
        candScroll.setBackgroundColor(Color.argb(a, 0, 0, 0))
        kbd.setBgAlpha(a)
        panel.setBgAlpha(a)
    }

    // ---------- 候选栏 ----------

    private fun clearCands() {
        setCandidatesExpanded(false)
        candPages.removeAllViews()
        candidatePageScrolls.clear()
        candScroll.scrollTo(0, 0)
    }

    private fun setCandidatesExpanded(expanded: Boolean) {
        if (!::candScroll.isInitialized || candidatesExpanded == expanded) return
        candidatesExpanded = expanded
        val d = resources.displayMetrics.density
        val lp = candScroll.layoutParams
        lp.height = ((if (expanded) 156 else 52) * d).toInt()
        candScroll.layoutParams = lp
    }

    private fun showCands(items: List<String>) {
        candPages.removeAllViews()
        candidatePageScrolls.clear()
        val d = resources.displayMetrics.density
        val pageWidth = resources.displayMetrics.widthPixels
        var page = newCandidatePage(pageWidth)
        var rows = ((page.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout)
        var row = newCandidateRow()
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))
        var usedWidth = 0
        var rowsInPage = 1

        for (i in items.indices) {
            val tv = TextView(this).apply {
                text = items[i]
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                setOnClickListener { pick(i) }
            }
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec((52 * d).toInt(), View.MeasureSpec.EXACTLY)
            )
            val itemWidth = tv.measuredWidth.coerceAtMost(pageWidth)
            if (usedWidth > 0 && usedWidth + itemWidth > pageWidth) {
                if (rowsInPage >= 12) {
                    page = newCandidatePage(pageWidth)
                    rows = ((page.getChildAt(0) as ScrollView).getChildAt(0) as LinearLayout)
                    rowsInPage = 0
                }
                row = newCandidateRow()
                rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))
                rowsInPage++
                usedWidth = 0
            }
            row.addView(tv, LinearLayout.LayoutParams(itemWidth, ViewGroup.LayoutParams.MATCH_PARENT))
            usedWidth += itemWidth
        }
        candScroll.scrollTo(0, 0)
        candidatePageScrolls.forEach { it.scrollTo(0, 0) }
    }

    private fun newCandidateRow(): LinearLayout =
        LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun newCandidatePage(pageWidth: Int): LinearLayout {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        candidatePageScrolls.add(scroll)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        candPages.addView(page, LinearLayout.LayoutParams(pageWidth, ViewGroup.LayoutParams.MATCH_PARENT))
        return page
    }

    private fun refreshTyped() {
        cands = lex.candidates(composing.toString())
        showCands(cands.map { it.text })
    }

    private fun pick(i: Int) {
        val ic = currentInputConnection ?: return
        if (mode == 1) {
            if (i >= cands.size) return
            val cd = cands[i]
            val used = minOf(cd.used, composing.length)
            ic.beginBatchEdit()
            ic.commitText(cd.text, 1)
            composing.delete(0, used)
            if (composing.isNotEmpty()) ic.setComposingText(composing.toString(), 1)
            ic.endBatchEdit()
            if (composing.isEmpty()) {
                mode = 0
                clearCands()
            } else {
                refreshTyped()
            }
        } else if (mode == 2) {
            if (i >= glideWords.size) return
            ic.commitText(glideWords[i], 1)
            mode = 0
            clearCands()
        }
    }

    private fun commitRaw() {
        val ic = currentInputConnection ?: return
        ic.commitText(composing.toString(), 1)
        composing.setLength(0)
        mode = 0
        clearCands()
    }

    private fun finishGlide() {
        currentInputConnection?.finishComposingText()
        mode = 0
        clearCands()
    }

    /** 把正在输入的拼音全部落到输入框 */
    private fun settleTyped() {
        if (mode != 1) return
        if (cands.isNotEmpty()) pick(0)
        if (mode == 1 && composing.isNotEmpty()) commitRaw()
    }

    private fun settle() {
        if (mode == 1) settleTyped()
        if (mode == 2) finishGlide()
    }

    // ---------- 键盘事件 ----------

    override fun onLetter(c: Char) {
        val ic = currentInputConnection ?: return
        if (mode == 2) finishGlide()
        composing.append(c)
        mode = 1
        ic.setComposingText(composing.toString(), 1)
        refreshTyped()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        when (mode) {
            1 -> {
                if (composing.isEmpty()) {
                    mode = 0
                    clearCands()
                    return
                }
                composing.setLength(composing.length - 1)
                if (composing.isEmpty()) {
                    ic.setComposingText("", 1)
                    mode = 0
                    clearCands()
                } else {
                    ic.setComposingText(composing.toString(), 1)
                    refreshTyped()
                }
            }
            2 -> {
                ic.setComposingText("", 1)
                mode = 0
                clearCands()
            }
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        when (mode) {
            1 -> if (cands.isNotEmpty()) pick(0) else commitRaw()
            2 -> finishGlide()
            else -> ic.commitText(" ", 1)
        }
    }

    override fun onEnter() {
        when (mode) {
            1 -> commitRaw()
            2 -> finishGlide()
            else -> doEnter()
        }
    }

    private fun doEnter() {
        val opts = currentInputEditorInfo?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnter = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnter && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onText(s: String) {
        val ic = currentInputConnection ?: return
        settle()
        ic.commitText(s, 1)
    }

    override fun onGlide(xs: FloatArray, ys: FloatArray, n: Int) {
        val ic = currentInputConnection ?: return
        settle()
        val hits = decoder.decode(xs, ys, n, 8)
        if (hits.isEmpty()) return
        val words = ArrayList<String>()
        for (h in hits) {
            for (w in lex.wordsFor(h.py, 3)) {
                if (!words.contains(w)) words.add(w)
            }
        }
        if (words.isEmpty()) return
        glideWords = words
        mode = 2
        ic.setComposingText(words[0], 1)
        showCands(words)
    }

    // ---------- 工具行 ----------

    override fun onTool(name: String) {
        if (name == "clip") {
            togglePanel(1)
            return
        }
        if (name == "phrase") {
            togglePanel(2)
            return
        }
        settle()
        val ic = currentInputConnection
        when (name) {
            "hide" -> {
                hidePanel()
                requestHideSelf(0)
            }
            "clear" -> {
                ic?.performContextMenuAction(android.R.id.selectAll)
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            "select_copy" -> {
                ic?.performContextMenuAction(android.R.id.selectAll)
                ic?.performContextMenuAction(android.R.id.copy)
            }
            "paste_enter" -> {
                ic?.performContextMenuAction(android.R.id.paste)
                doEnter()
            }
            "undo" -> ctrlKey(KeyEvent.KEYCODE_Z)
            "left" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            "left_home" -> ic?.let {
                it.performContextMenuAction(android.R.id.selectAll)
                it.setSelection(0, 0)
            }
            "right_end" -> ic?.let {
                it.performContextMenuAction(android.R.id.selectAll)
                val selectedLength = it.getSelectedText(0)?.length
                    ?: ((it.getTextBeforeCursor(100000, 0)?.length ?: 0) +
                        (it.getTextAfterCursor(100000, 0)?.length ?: 0))
                it.setSelection(selectedLength, selectedLength)
            }
        }
    }

    private fun ctrlKey(code: Int) {
        val ic = currentInputConnection ?: return
        val t = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
    }

    // ---------- 剪贴板与常用语 ----------

    private fun grabClip() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val t = clip.getItemAt(0).coerceToText(this).toString().trim()
            if (t.isEmpty() || t.length > 2000) return
            clips.remove(t)
            clips.add(0, t)
            while (clips.size > 30) clips.removeAt(clips.size - 1)
            store.putList("clips", clips)
        } catch (e: Exception) {
        }
    }

    private fun togglePanel(kind: Int) {
        if (panelKind == kind) {
            hidePanel()
            return
        }
        if (kind == 1) grabClip()
        settle()
        panelKind = kind
        panel.show(kind, if (kind == 1) clips else phrases)
        panel.visibility = View.VISIBLE
        kbd.activeTool = if (kind == 1) "clip" else "phrase"
    }

    private fun hidePanel() {
        panelKind = 0
        panel.visibility = View.GONE
        kbd.activeTool = null
    }

    override fun onPanelPick(text: String) {
        settle()
        currentInputConnection?.commitText(text, 1)
        hidePanel()
    }

    override fun onPanelBack() {
        hidePanel()
    }

    override fun onPanelDelete(index: Int) {
        val items = if (panelKind == 1) clips else phrases
        if (index !in items.indices) return
        items.removeAt(index)
        if (panelKind == 1) {
            store.putList("clips", clips)
            panel.show(1, clips)
        } else {
            store.putList("phrases", phrases)
            panel.show(2, phrases)
        }
    }

    override fun onPanelAction(kind: Int) {
        if (kind == 1) {
            clips.clear()
            store.putList("clips", clips)
            panel.show(1, clips)
        } else {
            settle()
            val ic = currentInputConnection ?: return
            val b = ic.getTextBeforeCursor(2000, 0)?.toString() ?: ""
            val s = ic.getSelectedText(0)?.toString() ?: ""
            val a = ic.getTextAfterCursor(2000, 0)?.toString() ?: ""
            val t = (b + s + a).trim()
            if (t.isNotEmpty() && !phrases.contains(t) && phrases.size < 50) {
                phrases.add(0, t)
                store.putList("phrases", phrases)
            }
            panel.show(2, phrases)
        }
    }

}


/** Candidate bar: swipe up/down to expand/collapse, swipe left/right to page,
 * long-press then drag vertically to scroll through the current page. */
private class CandidateScrollView(
    context: Context,
    private val onVerticalSwipe: (Boolean) -> Unit,
    private val onLongPressScroll: (Int) -> Unit
) : HorizontalScrollView(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var verticalGesture = false
    private var longPressed = false
    private val longPress = Runnable { longPressed = true }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastY = ev.y
                verticalGesture = false
                longPressed = false
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, 450L)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!verticalGesture && kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    verticalGesture = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!verticalGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                handler.removeCallbacks(longPress)
                val pageWidth = width.coerceAtLeast(1)
                val maxScroll = ((getChildAt(0)?.width ?: width) - width).coerceAtLeast(0)
                val target = ((scrollX + pageWidth / 2) / pageWidth) * pageWidth
                smoothScrollTo(target.coerceIn(0, maxScroll), 0)
            }
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (longPressed) onLongPressScroll((lastY - event.y).toInt())
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (!longPressed) onVerticalSwipe(event.y < downY)
                verticalGesture = false
                longPressed = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                verticalGesture = false
                longPressed = false
            }
        }
        return true
    }
}
