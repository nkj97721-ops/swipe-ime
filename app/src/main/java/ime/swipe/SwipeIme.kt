package ime.swipe

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.Html
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.Collections
import java.util.concurrent.Executors

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

    private var clips: MutableList<ClipEntry> = mutableListOf()
    private var phrases: MutableList<String> = mutableListOf()
    private var panelKind = 0 // 0 无 1 剪贴板 2 常用语
    private var clipboardManager: ClipboardManager? = null
    private val clipboardExecutor = Executors.newSingleThreadExecutor()
    private val glideExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var glideGeneration = 0
    private var sensitiveEditor = false
    private var contextPreviousWord = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingClipFiles = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var serviceDestroyed = false

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
        clips = store.getClipEntries()
        cleanupClipboardFiles()
        phrases = store.getList("phrases", DEFAULT_PHRASES)
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        store.prefs().registerOnSharedPreferenceChangeListener(prefListener)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
        Thread {
            try {
                lex.load(applicationContext)
            } catch (_: Exception) {
                // 词库读取失败时保持输入法可用；候选栏不会因后台异常导致服务崩溃。
            } finally {
                mainHandler.post {
                    if (!serviceDestroyed && mode == 1 && composing.isNotEmpty()) {
                        refreshTyped()
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        clipboardManager = null
        serviceDestroyed = true
        clipboardExecutor.shutdownNow()
        glideGeneration++
        glideExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        if (::store.isInitialized) store.prefs().unregisterOnSharedPreferenceChangeListener(prefListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val d = resources.displayMetrics.density
        val rowPx = (57 * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        // 键盘上移后露出的区域保持纯黑，不显示透明空洞。
        root.setBackgroundColor(Color.BLACK)
        root.clipChildren = false
        root.clipToPadding = false

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
        keyboardFrame.clipChildren = false
        keyboardFrame.clipToPadding = false
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
        glideGeneration++
        sensitiveEditor = isSensitiveEditor(info ?: currentInputEditorInfo)
        contextPreviousWord = ""
        composing.setLength(0)
        mode = 0
        if (::candRow.isInitialized) clearCands()
        if (::panel.isInitialized) hidePanel()
        if (::kbd.isInitialized) {
            val inputClass = (info?.inputType ?: currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_CLASS
            kbd.setEditorNumericMode(inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE)
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // candidatesStart/candidatesEnd 是编辑器候选区，不是 composing 文本范围；
        // 用它们判断拼音长度会误清空候选栏。仅在用户产生非折叠选区时结束当前组合。
        if (newSelStart != newSelEnd) {
            if (mode == 1) {
                currentInputConnection?.finishComposingText()
                composing.setLength(0)
                mode = 0
                cands = emptyList()
                clearCands()
            } else if (mode == 2) {
                currentInputConnection?.finishComposingText()
                mode = 0
                glideWords = emptyList()
                clearCands()
            }
        }
    }

    private fun previousChineseWord(text: String): String {
        val value = text.trimEnd()
        var start = value.length
        while (start > 0 && start >= value.length - 4) {
            val code = value[start - 1].code
            if (code !in 0x4E00..0x9FFF) break
            start--
        }
        return value.substring(start)
    }

    private fun isSensitiveEditor(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputType = info.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val password = (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) || (inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        return password || (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        glideGeneration++
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
        if (sensitiveEditor) {
            cands = emptyList()
            clearCands()
            return
        }
        val base = lex.candidates(composing.toString())
        val contextScores = store.contextScores(contextPreviousWord, base.map { it.text })
        cands = if (contextScores.isEmpty()) base else base.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Cand>> { contextScores[it.value.text] ?: 0 }.thenBy { it.index })
            .map { it.value }
        showCands(cands.map { it.text })
    }

    private fun pick(i: Int) {
        val ic = currentInputConnection ?: return
        if (mode == 1) {
            if (i >= cands.size) return
            val cd = cands[i]
            if (!sensitiveEditor && contextPreviousWord.isNotEmpty()) store.learnContext(contextPreviousWord, cd.text)
            contextPreviousWord = cd.text
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
            val chosen = glideWords[i]
            if (!sensitiveEditor && contextPreviousWord.isNotEmpty()) store.learnContext(contextPreviousWord, chosen)
            contextPreviousWord = chosen
            ic.commitText(chosen, 1)
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

    // ---------- 空格上下拖动：只伸缩最底部工具行，键盘顶部保持贴合 ----------

    override fun onKeyboardDrag(deltaY: Float) {
        if (!::kbd.isInitialized) return
        kbd.adjustBottomRowHeight(-deltaY)
    }

    // ---------- 键盘事件 ----------

    override fun onLetter(c: Char) {
        val ic = currentInputConnection ?: return
        if (composing.isEmpty() && !sensitiveEditor) contextPreviousWord = previousChineseWord(ic.getTextBeforeCursor(32, 0)?.toString().orEmpty())
        glideGeneration++
        if (mode == 2) finishGlide()
        if (sensitiveEditor) {
            ic.commitText(c.lowercaseChar().toString(), 1)
            return
        }
        composing.append(c.lowercaseChar())
        mode = 1
        ic.setComposingText(composing.toString(), 1)
        refreshTyped()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        glideGeneration++
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
        glideGeneration++
        when (mode) {
            1 -> if (cands.isNotEmpty()) pick(0) else commitRaw()
            2 -> finishGlide()
            else -> ic.commitText(" ", 1)
        }
    }

    override fun onEnter() {
        glideGeneration++
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
        glideGeneration++
        settle()
        ic.commitText(s, 1)
    }

    override fun onGlide(xs: FloatArray, ys: FloatArray, n: Int) {
        val ic = currentInputConnection ?: return
        if (sensitiveEditor) return
        if (mode != 1) contextPreviousWord = previousChineseWord(ic.getTextBeforeCursor(32, 0)?.toString().orEmpty())
        settle()
        val generation = ++glideGeneration
        val pathX = xs.copyOf(n)
        val pathY = ys.copyOf(n)
        try {
            glideExecutor.execute {
                val words = try {
                    val hits = decoder.decode(pathX, pathY, n, 8)
                    val found = ArrayList<String>()
                    for (hit in hits) {
                        for (word in lex.wordsFor(hit.py, 3)) {
                            if (!found.contains(word)) found.add(word)
                        }
                    }
                    found
                } catch (_: Exception) {
                    emptyList()
                }
                mainHandler.post {
                    if (serviceDestroyed || generation != glideGeneration || ic !== currentInputConnection || words.isEmpty()) return@post
                    val contextScores = store.contextScores(contextPreviousWord, words)
                    glideWords = if (contextScores.isEmpty()) words else words.withIndex()
                        .sortedWith(compareByDescending<IndexedValue<String>> { contextScores[it.value] ?: 0 }.thenBy { it.index })
                        .map { it.value }
                    mode = 2
                    // 候选栏顺序必须与点击索引一致，使用经过上下文排序的同一列表。
                    ic.setComposingText(glideWords.first(), 1)
                    showCands(glideWords)
                }
            }
        } catch (_: Exception) {
            // The service may be shutting down; discard this gesture.
        }
    }

    // ---------- 工具行 ----------

    override fun onTool(name: String) {
        glideGeneration++
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
            "paste_enter" -> pasteClipboardText(ic) { pasted ->
                if (pasted && ic === currentInputConnection) doEnter()
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

    private fun copyAllText(ic: InputConnection?) {
        if (ic == null) return
        try {
            // 不依赖 performContextMenuAction 的返回值；用 ExtractedText 的选区边界
            // 验证确实选中了整个文档，避免把用户原有的局部选区误当成全文。
            ic.performContextMenuAction(android.R.id.selectAll)
            val extracted = ic.getExtractedText(ExtractedTextRequest().apply {
                hintMaxChars = 1_000_000
                hintMaxLines = 10_000
            }, 0) ?: return
            val fullText = extracted.text?.toString() ?: return
            val selectionStart = minOf(extracted.selectionStart, extracted.selectionEnd)
            val selectionEnd = maxOf(extracted.selectionStart, extracted.selectionEnd)
            if (selectionStart != 0 || selectionEnd != fullText.length) return
            val selected = ic.getSelectedText(0)?.toString() ?: return
            if (selected.isEmpty() || selected != fullText) return

            val cm = clipboardManager ?: return
            ic.performContextMenuAction(android.R.id.copy)
            if (!clipboardHasText(cm.primaryClip, selected)) {
                cm.setPrimaryClip(ClipData.newPlainText("text", selected))
            }
            if (!clipboardHasText(cm.primaryClip, selected)) return

            // 仅在编辑器明确返回完整文档内容时才把光标收至文末，避免错误偏移。
            val before = ic.getTextBeforeCursor(1_000_000, 0)?.toString()
            if (before == selected) ic.setSelection(selected.length, selected.length)
            grabClip()
        } catch (_: Exception) {
            // 当前编辑器不支持全选/复制时不伪造成功。
        }
    }

    private fun pasteClipboardText(ic: InputConnection?, onComplete: (Boolean) -> Unit) {
        if (ic == null) {
            onComplete(false)
            return
        }
        val clip = try { clipboardManager?.primaryClip } catch (_: Exception) { null }
        if (clip == null || clip.itemCount == 0) {
            onComplete(false)
            return
        }
        try {
            clipboardExecutor.execute {
                val items = try { readClipboardItems(clip) } catch (_: Exception) { emptyList() }
                mainHandler.post {
                    if (serviceDestroyed) return@post
                    rememberClipItems(items)
                    val pasted = ic === currentInputConnection &&
                        items.firstOrNull()?.let { pasteClipEntry(ic, it) } == true
                    onComplete(pasted)
                }
            }
        } catch (_: Exception) {
            onComplete(false)
        }
    }

    private fun pasteClipEntry(ic: InputConnection?, entry: ClipEntry): Boolean {
        if (ic == null) return false
        return try {
            if (entry.fileName != null && !entry.mimeType.isNullOrBlank()) {
                val uri = Uri.Builder()
                    .scheme("content")
                    .authority(packageName + ".clipboard")
                    .appendPath(entry.fileName)
                    .appendQueryParameter("mime", entry.mimeType)
                    .appendQueryParameter("name", entry.label.ifBlank { entry.fileName })
                    .build()
                val mime = entry.mimeType
                val accepted = currentInputEditorInfo?.contentMimeTypes?.any { supported ->
                    supported.equals(mime, ignoreCase = true) ||
                        supported == "*/*" ||
                        (supported.endsWith("/*") &&
                            mime.startsWith(supported.substringBefore('/') + "/", ignoreCase = true))
                } == true
                if (Build.VERSION.SDK_INT >= 25 && accepted) {
                    val description = ClipDescription(
                        entry.label.ifBlank { "attachment" }, arrayOf(mime)
                    )
                    val content = InputContentInfo(uri, description, null)
                    if (ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) {
                        return true
                    }
                }

                // 不支持 IME content 的编辑器由系统粘贴菜单处理 URI。
                val cm = clipboardManager ?: return false
                cm.setPrimaryClip(ClipData.newUri(contentResolver, entry.label.ifBlank { "attachment" }, uri))
                return ic.performContextMenuAction(android.R.id.paste)
            }

            val text = entry.text ?: return false
            if (!entry.html.isNullOrEmpty()) {
                val cm = clipboardManager ?: return false
                cm.setPrimaryClip(ClipData.newHtmlText(entry.label.ifBlank { "text" }, text, entry.html))
                val handled = ic.performContextMenuAction(android.R.id.paste)
                // A handled paste can update the editor asynchronously. Never append a
                // second plain-text copy merely because cursor snapshots have not changed yet.
                if (handled) return true
            }
            // 纯文本直接提交，保留空格、换行和完整内容。
            ic.commitText(text, 1)
        } catch (_: Exception) {
            false
        }
    }

    private fun clipboardHasText(clip: ClipData?, expected: String): Boolean {
        if (clip == null) return false
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = item.text?.toString()
                ?: item.htmlText?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            if (text == expected) return true
        }
        return false
    }

    private fun readClipboardItems(clip: ClipData?): List<ClipEntry> {
        if (clip == null || clip.itemCount == 0) return emptyList()
        val result = ArrayList<ClipEntry>()
        for (i in 0 until clip.itemCount) {
            try {
                val item = clip.getItemAt(i)
                val html = item.htmlText
                val uri = item.uri ?: item.intent?.data
                // ClipDescription lists MIME types supported by the whole clip, not one
                // MIME type per item. Prefer the URI's own resolver type; otherwise use
                // the first advertised type instead of indexing by item position.
                val descriptionMime = (0 until clip.description.mimeTypeCount)
                    .asSequence()
                    .map { clip.description.getMimeType(it) }
                    .firstOrNull()
                val mime = uri?.let { contentResolver.getType(it) } ?: descriptionMime
                val text = item.text?.toString()
                    ?: html?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
                    ?: if (mime?.startsWith("text/") == true && uri != null) {
                        item.coerceToText(this)?.toString()
                    } else null
                val fileName = uri?.let { copyClipboardUriToFile(it, mime) }
                if (!text.isNullOrEmpty() || fileName != null) {
                    val digest = fileName?.let { digestForFile(File(filesDir, "clipboard/" + it)) }
                    result.add(
                        ClipEntry(
                            text = text,
                            html = html,
                            fileName = fileName,
                            mimeType = if (fileName != null) (mime ?: "application/octet-stream") else null,
                            label = clip.description.label?.toString().orEmpty(),
                            digest = digest
                        )
                    )
                }
            } catch (_: Exception) {
                // 一个损坏或权限失效的项目不能让同一剪贴板里的其他项目丢失。
                continue
            }
        }
        return result
    }

    private fun copyClipboardUriToFile(uri: Uri, mime: String?): String? {
        val dir = File(filesDir, "clipboard")
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = UUID.randomUUID().toString()
        val temp = File(dir, name + ".tmp")
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        val finalName = if (ext.isNullOrBlank()) name else name + "." + ext
        val target = File(dir, finalName)
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 20L * 1024L * 1024L) {
                            temp.delete()
                            return null
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            pendingClipFiles.add(finalName)
            finalName
        } catch (_: Exception) {
            temp.delete()
            target.delete()
            null
        }
    }

    private fun digestForFile(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    private fun rememberClipItems(items: List<ClipEntry>) {
        if (sensitiveEditor || items.isEmpty()) return
        for (entry in items.asReversed()) {
            entry.fileName?.let { pendingClipFiles.remove(it) }
            clips.removeAll { it.sameContent(entry) }
            clips.add(0, entry)
        }
        while (clips.size > 30) clips.removeAt(clips.lastIndex)
        store.putClipEntries(clips)
        cleanupClipboardFiles()
        if (panelKind == 1 && ::panel.isInitialized && panel.visibility == View.VISIBLE) {
            panel.showClipboard(clips)
        }
    }

    private fun grabClip() {
        if (sensitiveEditor) return
        val clip = try { clipboardManager?.primaryClip } catch (_: Exception) { null } ?: return
        if (clip.itemCount == 0) return
        try {
            clipboardExecutor.execute {
                val items = try { readClipboardItems(clip) } catch (_: Exception) { emptyList() }
                mainHandler.post {
                    if (!serviceDestroyed) rememberClipItems(items)
                }
            }
        } catch (_: Exception) {
            // 输入法销毁或线程池关闭时保留已有历史。
        }
    }

    private fun cleanupClipboardFiles() {
        val pending = synchronized(pendingClipFiles) { pendingClipFiles.toSet() }
        val keep = clips.mapNotNull { it.fileName }.toSet() + pending
        File(filesDir, "clipboard").listFiles()?.forEach { file ->
            if (file.name !in keep) file.delete()
        }
    }

    private fun togglePanel(kind: Int) {
        if (sensitiveEditor) return
        if (panelKind == kind) {
            hidePanel()
            return
        }
        if (kind == 1) grabClip()
        settle()
        panelKind = kind
        if (kind == 1) panel.showClipboard(clips) else panel.show(kind, phrases)
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

    override fun onPanelPickClip(entry: ClipEntry) {
        settle()
        if (pasteClipEntry(currentInputConnection, entry)) hidePanel()
    }

    override fun onPanelBack() {
        hidePanel()
    }

    override fun onPanelDelete(index: Int) {
        if (panelKind == 1) {
            if (index !in clips.indices) return
            val removed = clips.removeAt(index)
            removed.fileName?.let { File(filesDir, "clipboard/" + it).delete() }
            store.putClipEntries(clips)
            panel.showClipboard(clips)
        } else {
            if (index !in phrases.indices) return
            phrases.removeAt(index)
            store.putList("phrases", phrases)
            panel.show(2, phrases)
        }
    }

    override fun onPanelAction(kind: Int) {
        if (kind == 1) {
            clips.clear()
            store.putClipEntries(clips)
            cleanupClipboardFiles()
            panel.showClipboard(clips)
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
