package ime.swipe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.Html
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Gravity
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
    private lateinit var keyboardFrame: FrameLayout
    private lateinit var panel: PanelView
    private lateinit var candRow: LinearLayout
    private lateinit var candScroll: HorizontalScrollView

    private val composing = StringBuilder()
    private var mode = 0 // 0 空闲 1 拼音输入 2 滑行结果
    private var cands: List<Cand> = emptyList()
    private var glideWords: List<String> = emptyList()

    private var clips: MutableList<String> = mutableListOf()
    private var phrases: MutableList<String> = mutableListOf()
    private var panelKind = 0 // 0 无 1 剪贴板 2 常用语
    private var clipboardManager: ClipboardManager? = null

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
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
        Thread { lex.load(applicationContext) }.start()
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        clipboardManager = null
        if (::store.isInitialized) store.prefs().unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        val rowPx = (57 * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        candScroll = HorizontalScrollView(this)
        candScroll.isHorizontalScrollBarEnabled = false
        candRow = LinearLayout(this)
        candRow.orientation = LinearLayout.HORIZONTAL
        candScroll.addView(
            candRow,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(candScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * d).toInt()))

        keyboardFrame = FrameLayout(this)
        kbd = KeyboardView(this, this, decoder, store)
        keyboardFrame.addView(kbd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowPx * 6))
        panel = PanelView(this, this)
        panel.visibility = View.GONE
        keyboardFrame.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowPx * 4, Gravity.TOP))
        root.addView(keyboardFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowPx * 6))

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
        candRow.removeAllViews()
    }

    private fun showCands(items: List<String>) {
        candRow.removeAllViews()
        val d = resources.displayMetrics.density
        for (i in items.indices) {
            val tv = TextView(this)
            tv.text = items[i]
            tv.setTextColor(Color.WHITE)
            tv.textSize = 22f
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            tv.setOnClickListener { pick(i) }
            candRow.addView(
                tv,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
        candScroll.scrollTo(0, 0)
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

    // ---------- 键盘高度：同步调整键盘容器 ----------

    override fun onKeyboardHeightChanged(heightPx: Int) {
        if (!::kbd.isInitialized || !::keyboardFrame.isInitialized) return
        val keyboardParams = kbd.layoutParams
        keyboardParams.height = heightPx
        kbd.layoutParams = keyboardParams
        val frameParams = keyboardFrame.layoutParams as LinearLayout.LayoutParams
        frameParams.height = heightPx
        keyboardFrame.layoutParams = frameParams
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
            "select_copy" -> copyAllText(ic)
            "paste_enter" -> {
                if (pasteClipboardText(ic)) doEnter()
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

    // ---------- 可靠复制、粘贴与剪贴板历史 ----------

    private fun copyAllText(ic: android.view.inputmethod.InputConnection?) {
        if (ic == null) return
        try {
            if (!ic.performContextMenuAction(android.R.id.selectAll)) return
            val selected = ic.getSelectedText(0)?.toString() ?: return
            if (selected.isEmpty()) return

            val cm = clipboardManager ?: return
            val actionHandled = ic.performContextMenuAction(android.R.id.copy)
            val copied = readClipboardItems(cm.primaryClip).any { it == selected }
            if (!actionHandled && !copied) return

            // 全选复制后收起选区，光标放在文本末尾。
            ic.setSelection(selected.length, selected.length)
            grabClip()
        } catch (_: Exception) {
            // 不支持上下文菜单的编辑器不伪报复制成功。
        }
    }

    private fun pasteClipboardText(ic: android.view.inputmethod.InputConnection?): Boolean {
        if (ic == null) return false
        return try {
            if (ic.performContextMenuAction(android.R.id.paste)) {
                true
            } else {
                // 对不实现系统粘贴菜单的输入框，退回纯文本提交。
                val text = readClipboardItems(clipboardManager?.primaryClip).firstOrNull()
                    ?: return false
                ic.commitText(text, 1)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readClipboardItems(clip: ClipData?): List<String> {
        if (clip == null || clip.itemCount == 0) return emptyList()
        val result = LinkedHashSet<String>()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = when {
                item.text != null -> item.text.toString()
                !item.htmlText.isNullOrEmpty() -> Html.fromHtml(
                    item.htmlText!!, Html.FROM_HTML_MODE_LEGACY
                ).toString()
                else -> null
            }
            if (!text.isNullOrEmpty()) result.add(text)
        }
        return result.toList()
    }

    private fun grabClip() {
        try {
            val items = readClipboardItems(clipboardManager?.primaryClip)
            if (items.isEmpty()) return
            for (text in items.asReversed()) {
                clips.remove(text)
                clips.add(0, text)
            }
            while (clips.size > 30) clips.removeAt(clips.size - 1)
            store.putList("clips", clips)
        } catch (_: Exception) {
            // 读取失败时保留已有历史。
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
