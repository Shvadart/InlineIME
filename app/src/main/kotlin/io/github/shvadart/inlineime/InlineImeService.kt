package io.github.shvadart.inlineime

import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import io.github.shvadart.inlineime.suggestion.CalculatorSuggestionProvider
import io.github.shvadart.inlineime.suggestion.Suggestion
import io.github.shvadart.inlineime.suggestion.SuggestionProvider

class InlineImeService : InputMethodService() {
    private enum class Language { RU, EN }
    private data class ClipboardEntry(
        val text: String,
        val pinned: Boolean,
        val sourceLength: Int = text.length,
        val sourceHash: Int = text.hashCode(),
    )
    private data class EditorSnapshot(val text: String, val selectionStart: Int, val selectionEnd: Int)

    private var language = Language.RU
    private var shift = true
    private var capsLock = false
    private var symbols = false
    private var selectionMode = false
    private var selectionAnchor: Int? = null
    private var lastShiftTap = 0L

    private lateinit var lettersContainer: LinearLayout
    private lateinit var keyboardContent: LinearLayout
    private lateinit var suggestionButton: TextView
    private lateinit var selectButton: TextView
    private lateinit var languageButton: TextView
    private lateinit var shiftButton: TextView
    private lateinit var spaceButton: TextView

    private val gestureHandler = Handler(Looper.getMainLooper())
    private var deleteHoldStartedAt = 0L
    private var deleteRepeatRunnable: Runnable? = null
    private var deleteClearPopup: PopupWindow? = null
    private var deleteClearTargeted = false
    private var deleteWordHistoryCaptured = false

    private var activeSuggestion: Suggestion? = null
    private val clipboardEntries = mutableListOf<ClipboardEntry>()
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }
    private var clipboardListening = false
    private var clipboardHistoryVisible = false
    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()
    private var restoringEditorHistory = false
    private val clipboardPrefs by lazy { getSharedPreferences("clipboard_history", MODE_PRIVATE) }

    private val suggestionProviders: List<SuggestionProvider> = listOf(
        CalculatorSuggestionProvider(),
    )

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
            setBackgroundColor(COLOR_BACKGROUND)
            setOnApplyWindowInsetsListener { view, insets ->
                val navBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetBottom
                }
                view.setPadding(dp(4), dp(4), dp(4), maxOf(dp(24), navBottom + dp(18)))
                insets
            }
        }

        window?.window?.navigationBarColor = COLOR_BACKGROUND

        root.addView(buildEditingToolbar(), rowParams(dp(44)))

        suggestionButton = suggestionView().apply {
            visibility = View.GONE
            setOnClickListener {
                val suggestion = activeSuggestion ?: return@setOnClickListener
                currentInputConnection?.commitText(suggestion.text, 1)
                refreshSuggestion()
            }
        }
        root.addView(suggestionButton, rowParams(dp(42)))

        keyboardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(keyboardContent)

        showKeyboardContent()

        return root
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        undoStack.clear()
        redoStack.clear()
        loadClipboardHistory()
        startClipboardHistory()
        captureClipboard()
        refreshSuggestion()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopClipboardHistory()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        refreshSuggestion()
    }

    private fun buildEditingToolbar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun add(label: String, action: () -> Unit) {
            row.addView(
                toolbarButton(label).apply { setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, dp(40), 1f),
            )
        }

        add("|←") { moveToBoundary(toEnd = false) }
        add("↶") { performEditorHistory(undo = true) }
        add("▣") { performContextAction(android.R.id.selectAll) }
        add("↑") { sendNavigation(KeyEvent.KEYCODE_DPAD_UP) }
        add("←") { sendNavigation(KeyEvent.KEYCODE_DPAD_LEFT) }

        selectButton = toolbarButton("T").apply {
            setOnClickListener {
                selectionMode = !selectionMode
                selectionAnchor = if (selectionMode) {
                    currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.selectionStart
                } else {
                    null
                }
                updateSelectionButton()
            }
        }
        row.addView(selectButton, LinearLayout.LayoutParams(0, dp(40), 1f))

        add("→") { sendNavigation(KeyEvent.KEYCODE_DPAD_RIGHT) }
        add("↓") { sendNavigation(KeyEvent.KEYCODE_DPAD_DOWN) }
        add("⧉") { performContextAction(android.R.id.copy) }

        val pasteButton = toolbarButton("▤").apply {
            setOnClickListener { pasteFast() }
            setOnLongClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showClipboardHistory()
                true
            }
        }
        row.addView(pasteButton, LinearLayout.LayoutParams(0, dp(40), 1f))

        add("↷") { performEditorHistory(undo = false) }
        add("→|") { moveToBoundary(toEnd = true) }

        return row
    }

    private fun showKeyboardContent() {
        if (!::keyboardContent.isInitialized) return
        clipboardHistoryVisible = false
        keyboardContent.removeAllViews()
        keyboardContent.addView(
            buildEqualRow("1234567890".map { it.toString() }, ::commitTextKey, dp(48)),
        )
        lettersContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keyboardContent.addView(lettersContainer)
        keyboardContent.addView(buildBottomRow())
        renderLetterRows()
    }

    private fun showClipboardHistory(captureCurrent: Boolean = true) {
        if (!::keyboardContent.isInitialized) return
        clipboardHistoryVisible = true
        if (captureCurrent) captureClipboard(refreshUi = false)
        keyboardContent.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(toolbarButton("←").apply {
            setOnClickListener { showKeyboardContent() }
        }, LinearLayout.LayoutParams(dp(52), dp(44)))
        header.addView(TextView(this).apply {
            text = "Буфер обмена"
            textSize = 17f
            setTextColor(COLOR_KEY_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(toolbarButton("Очистить").apply {
            textSize = 14f
            setOnClickListener {
                clipboardEntries.removeAll { !it.pinned }
                saveClipboardHistory()
                showClipboardHistory(captureCurrent = false)
            }
        }, LinearLayout.LayoutParams(dp(92), dp(44)))
        keyboardContent.addView(header)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ordered = clipboardEntries.sortedWith(
            compareByDescending<ClipboardEntry> { it.pinned },
        )
        if (ordered.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "История пока пуста"
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(COLOR_TOOLBAR_TEXT)
            }, rowParams(dp(120)))
        } else {
            ordered.forEach { entry -> list.addView(clipboardHistoryRow(entry)) }
        }
        keyboardContent.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
    }

    private fun clipboardHistoryRow(entry: ClipboardEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = insetKeyBackground(COLOR_KEY)
        }
        val preview = TextView(this).apply {
            text = clipboardPreview(entry.text, entry.sourceLength)
            maxLines = 2
            textSize = 15f
            setTextColor(COLOR_KEY_TEXT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(8), dp(5))
            setOnClickListener {
                pasteText(entry.text)
                showKeyboardContent()
            }
        }
        row.addView(preview, LinearLayout.LayoutParams(0, dp(58), 1f))
        row.addView(toolbarButton(if (entry.pinned) "★" else "☆").apply {
            setOnClickListener {
                val index = clipboardEntries.indexOfFirst {
                    it.sourceLength == entry.sourceLength && it.sourceHash == entry.sourceHash
                }
                if (index >= 0) clipboardEntries[index] = entry.copy(pinned = !entry.pinned)
                saveClipboardHistory()
                showClipboardHistory()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(58)))
        row.addView(toolbarButton("✕").apply {
            setOnClickListener {
                clipboardEntries.removeAll {
                    it.sourceLength == entry.sourceLength && it.sourceHash == entry.sourceHash
                }
                saveClipboardHistory()
                showClipboardHistory(captureCurrent = false)
            }
        }, LinearLayout.LayoutParams(dp(48), dp(58)))
        return row
    }

    private fun startClipboardHistory() {
        if (clipboardListening) return
        getSystemService(ClipboardManager::class.java)
            .addPrimaryClipChangedListener(clipboardListener)
        clipboardListening = true
    }

    private fun stopClipboardHistory() {
        if (!clipboardListening) return
        getSystemService(ClipboardManager::class.java)
            .removePrimaryClipChangedListener(clipboardListener)
        clipboardListening = false
    }

    private fun clipboardPreview(text: String, sourceLength: Int): String {
        val compact = text.replace("\r", "").replace("\n", " ↵ ")
        if (compact.length <= CLIPBOARD_PREVIEW_LENGTH && sourceLength == text.length) return compact
        return compact.take(CLIPBOARD_PREVIEW_LENGTH) + "…  [$sourceLength симв.]"
    }

    private fun captureClipboard(refreshUi: Boolean = true) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0 || isSensitiveClipboard(clip.description)) return
        val value = clip.getItemAt(0).coerceToText(this)?.toString()?.trimEnd() ?: return
        if (value.isBlank()) return

        val sourceLength = value.length
        val sourceHash = value.hashCode()
        val storedValue = if (sourceLength > CLIPBOARD_HISTORY_ENTRY_LIMIT) {
            value.take(CLIPBOARD_HISTORY_ENTRY_LIMIT)
        } else {
            value
        }
        val existing = clipboardEntries.firstOrNull {
            it.sourceLength == sourceLength && it.sourceHash == sourceHash
        }
        clipboardEntries.removeAll {
            it.sourceLength == sourceLength && it.sourceHash == sourceHash
        }
        clipboardEntries.add(
            0,
            ClipboardEntry(storedValue, existing?.pinned == true, sourceLength, sourceHash),
        )
        trimClipboardHistory()
        saveClipboardHistory()
        if (refreshUi && clipboardHistoryVisible) showClipboardHistory()
    }

    private fun isSensitiveClipboard(description: ClipDescription): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
    }

    private fun trimClipboardHistory() {
        var unpinned = 0
        val iterator = clipboardEntries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.pinned && ++unpinned > CLIPBOARD_HISTORY_LIMIT) iterator.remove()
        }
    }

    private fun saveClipboardHistory() {
        val encoded = clipboardEntries.joinToString("\n") { entry ->
            val value = android.util.Base64.encodeToString(
                entry.text.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
            (if (entry.pinned) "1:" else "0:") +
                entry.sourceLength + ":" + entry.sourceHash + ":" + value
        }
        clipboardPrefs.edit().putString("entries", encoded).apply()
    }

    private fun loadClipboardHistory() {
        if (clipboardEntries.isNotEmpty()) return
        clipboardPrefs.getString("entries", null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                if (line.length < 3 || line[1] != ':') return@mapNotNull null
                runCatching {
                    val payload = line.substring(2)
                    val parts = payload.split(":", limit = 3)
                    if (parts.size == 3) {
                        ClipboardEntry(
                            String(android.util.Base64.decode(parts[2], android.util.Base64.DEFAULT)),
                            line[0] == '1',
                            parts[0].toInt(),
                            parts[1].toInt(),
                        )
                    } else {
                        val text = String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT))
                        ClipboardEntry(text, line[0] == '1')
                    }
                }.getOrNull()
            }
            ?.forEach(clipboardEntries::add)
    }

    private fun buildBottomRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(bottomKey("#1?") {
            symbols = !symbols
            renderLetterRows()
        }, weightedKeyParams(weight = 1.15f, height = dp(56)))

        languageButton = bottomKey("RU") {
            language = if (language == Language.RU) Language.EN else Language.RU
            languageButton.text = if (language == Language.RU) "RU" else "EN"
            renderLetterRows()
        }
        row.addView(languageButton, weightedKeyParams(weight = 1.0f, height = dp(56)))

        row.addView(bottomKey(",") { commitTextKey(",") }, weightedKeyParams(0.8f, dp(56)))

        spaceButton = bottomKey(if (language == Language.RU) "Русский" else "English") {
            commitTextKey(" ")
        }.apply {
            var downX = 0f
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - downX
                        if (kotlin.math.abs(dx) >= dp(48)) {
                            language = if (language == Language.RU) Language.EN else Language.RU
                            languageButton.text = if (language == Language.RU) "RU" else "EN"
                            text = if (language == Language.RU) "Русский" else "English"
                            renderLetterRows()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }
        row.addView(spaceButton, weightedKeyParams(4.2f, dp(56)))

        row.addView(bottomKey(".") { commitTextKey(".") }.apply {
            setOnLongClickListener {
                commitTextKey(",")
                true
            }
        }, weightedKeyParams(0.8f, dp(56)))

        row.addView(bottomKey("↵") {
            rememberEditorState()
            currentInputConnection?.commitText("\n", 1)
            syncAutoShiftFromCursor()
            refreshSuggestion()
        }, weightedKeyParams(1.2f, dp(56)))

        return row
    }

    private fun renderLetterRows() {
        if (!::lettersContainer.isInitialized) return
        lettersContainer.removeAllViews()

        if (symbols) {
            listOf("!?@#%&*+-=", "()[]{}<>", "\\/:;\"'€£¥").forEach { chars ->
                lettersContainer.addView(buildEqualRow(chars.map { it.toString() }, ::commitTextKey, dp(56)))
            }
            return
        }

        val rows = when (language) {
            Language.RU -> listOf(
                "йцукенгшщзх",
                "фывапролджэ",
            )
            Language.EN -> listOf(
                "qwertyuiop",
                "asdfghjkl",
            )
        }

        rows.forEachIndexed { index, chars ->
            val labels = chars.map { char ->
                val c = if (shift) char.uppercaseChar() else char
                c.toString()
            }
            val sideInset = if (index == 1) dp(14) else 0
            val row = buildEqualRow(labels, ::commitLetter, dp(56), sideInset) as LinearLayout
            if (language == Language.RU && index == 0) {
                val eIndex = chars.indexOf('е')
                if (eIndex >= 0) {
                    row.getChildAt(eIndex)?.setOnLongClickListener {
                        commitLetter(if (shift) "Ё" else "ё")
                        true
                    }
                }
            }
            lettersContainer.addView(row)
        }

        lettersContainer.addView(buildThirdLetterRow())

        if (::shiftButton.isInitialized) {
            updateShiftButton()
        }
    }

    private fun buildThirdLetterRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        shiftButton = specialKey("⇧").apply {
            setOnClickListener {
                val now = SystemClock.uptimeMillis()
                if (now - lastShiftTap <= DOUBLE_TAP_MS) {
                    capsLock = true
                    shift = true
                } else if (capsLock) {
                    capsLock = false
                    shift = false
                } else {
                    shift = !shift
                }
                lastShiftTap = now
                renderLetterRows()
            }
        }
        row.addView(shiftButton, weightedKeyParams(1.25f, dp(56)))

        val chars = when (language) {
            Language.RU -> "ячсмитьбю"
            Language.EN -> "zxcvbnm"
        }
        chars.forEach { char ->
            val label = (if (shift) char.uppercaseChar() else char).toString()
            row.addView(
                keyView(label).apply { setOnClickListener { commitLetter(label) } },
                weightedKeyParams(1f, dp(56)),
            )
        }

        row.addView(
            specialKey("⌫").apply {
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            rememberEditorState()
                            deleteHoldStartedAt = SystemClock.uptimeMillis()
                            deleteClearTargeted = false
                            deleteWordHistoryCaptured = false
                            startDeleteRepeat(view)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (deleteClearPopup != null) {
                                deleteClearTargeted =
                                    event.x in 0f..view.width.toFloat() &&
                                        event.y in -dp(76).toFloat()..-dp(4).toFloat()
                                updateDeleteClearPopup()
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val wasRepeating = deleteRepeatRunnable != null &&
                                SystemClock.uptimeMillis() - deleteHoldStartedAt >= DELETE_REPEAT_START_MS
                            stopDeleteRepeat()
                            if (deleteClearTargeted) {
                                clearAllText()
                            } else if (!wasRepeating) {
                                deleteOne()
                            } else {
                                syncAutoShiftFromCursor()
                                refreshSuggestion()
                            }
                            dismissDeleteClearPopup()
                            view.performClick()
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            stopDeleteRepeat()
                            dismissDeleteClearPopup()
                            true
                        }
                        else -> true
                    }
                }
            },
            weightedKeyParams(1.25f, dp(56)),
        )

        return row
    }

    private fun deleteOne(refresh: Boolean = true) {
        val ic = currentInputConnection ?: return
        if (refresh) rememberEditorState()
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            selectionMode = false
            selectionAnchor = null
            updateSelectionButton()
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        if (refresh) {
            syncAutoShiftFromCursor()
            refreshSuggestion()
        }
    }

    private fun startDeleteRepeat(anchor: View) {
        stopDeleteRepeat()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.uptimeMillis() - deleteHoldStartedAt
                if (elapsed >= DELETE_CLEAR_POPUP_MS && deleteClearPopup == null) {
                    showDeleteClearPopup(anchor)
                }
                if (!deleteClearTargeted && elapsed >= DELETE_REPEAT_START_MS) {
                    if (elapsed >= DELETE_WORD_MODE_MS) {
                        if (!deleteWordHistoryCaptured) {
                            rememberEditorState()
                            deleteWordHistoryCaptured = true
                        }
                        deleteWordBeforeCursor(refresh = false)
                    } else {
                        deleteOne(refresh = false)
                    }
                }
                val delay = when {
                    elapsed >= DELETE_WORD_MODE_MS -> DELETE_WORD_INTERVAL_MS
                    elapsed >= DELETE_FAST_MODE_MS -> DELETE_FAST_INTERVAL_MS
                    else -> DELETE_INITIAL_INTERVAL_MS
                }
                gestureHandler.postDelayed(this, delay)
            }
        }
        deleteRepeatRunnable = runnable
        gestureHandler.postDelayed(runnable, DELETE_REPEAT_START_MS)
    }

    private fun stopDeleteRepeat() {
        deleteRepeatRunnable?.let(gestureHandler::removeCallbacks)
        deleteRepeatRunnable = null
    }

    private fun deleteWordBeforeCursor(refresh: Boolean = true) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(128, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val trimmedEnd = before.indexOfLast { !it.isWhitespace() }
        val count = if (trimmedEnd < 0) {
            before.length
        } else {
            val wordStart = before.substring(0, trimmedEnd + 1)
                .indexOfLast { it.isWhitespace() } + 1
            before.length - wordStart
        }
        ic.deleteSurroundingText(count.coerceAtLeast(1), 0)
        if (refresh) {
            syncAutoShiftFromCursor()
            refreshSuggestion()
        }
    }

    private fun showDeleteClearPopup(anchor: View) {
        val label = TextView(this).apply {
            text = "✕"
            gravity = Gravity.CENTER
            textSize = 24f
            setTextColor(COLOR_KEY_TEXT)
            background = roundedBackground(COLOR_SPECIAL_KEY)
        }
        deleteClearPopup = PopupWindow(label, anchor.width, dp(60), false).apply {
            isTouchable = false
            isFocusable = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            isClippingEnabled = false
            showAsDropDown(anchor, 0, -anchor.height - dp(64))
        }
        updateDeleteClearPopup()
    }

    private fun updateDeleteClearPopup() {
        val label = deleteClearPopup?.contentView as? TextView ?: return
        label.background = roundedBackground(
            if (deleteClearTargeted) COLOR_ACTIVE else COLOR_SPECIAL_KEY,
        )
    }

    private fun dismissDeleteClearPopup() {
        deleteClearPopup?.dismiss()
        deleteClearPopup = null
        deleteClearTargeted = false
    }

    private fun buildEqualRow(
        labels: List<String>,
        action: (String) -> Unit,
        height: Int,
        sideInset: Int = 0,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(sideInset, 0, sideInset, 0)
        }

        labels.forEach { label ->
            row.addView(
                keyView(label).apply { setOnClickListener { action(label) } },
                weightedKeyParams(1f, height),
            )
        }
        return row
    }

    private fun commitLetter(text: String) {
        rememberEditorState()
        currentInputConnection?.commitText(text, 1)
        if (!capsLock) {
            val before = currentInputConnection?.getTextBeforeCursor(512, 0)?.toString().orEmpty()
            val shouldShift = shouldAutoShift(before)
            if (shift != shouldShift) {
                shift = shouldShift
                renderLetterRows()
            }
        }
        refreshSuggestion()
    }

    private fun commitTextKey(text: String) {
        rememberEditorState()
        currentInputConnection?.commitText(text, 1)
        syncAutoShiftFromCursor()
        refreshSuggestion()
    }

    private fun syncAutoShiftFromCursor() {
        if (capsLock) return
        val before = currentInputConnection?.getTextBeforeCursor(512, 0)?.toString().orEmpty()
        val shouldShift = shouldAutoShift(before)
        if (shift != shouldShift) {
            shift = shouldShift
            renderLetterRows()
        }
    }

    private fun shouldAutoShift(before: String): Boolean {
        if (before.isEmpty() || before.lastOrNull() == '\n') return true

        // Sentence capitalization starts only after punctuation followed by whitespace.
        // While punctuation is still attached to the previous token we keep lowercase so
        // domains/e-mails/URLs such as mail.ru and gmail.com don't become mail.Ru/gmail.Com.
        if (before.lastOrNull()?.isWhitespace() == true) {
            val trimmed = before.trimEnd()
            return trimmed.lastOrNull() in setOf('.', '!', '?')
        }

        return false
    }

    private fun enableAutoShift() {
        if (capsLock) return
        val before = currentInputConnection?.getTextBeforeCursor(512, 0)?.toString().orEmpty()
        val shouldShift = shouldAutoShift(before)
        if (shift != shouldShift) {
            shift = shouldShift
            renderLetterRows()
        }
    }

    private fun buildNumberRow(): View =
        buildEqualRow("1234567890".map { it.toString() }, ::commitTextKey, dp(48))

    private fun clearAllText() {
        val ic = currentInputConnection ?: return
        rememberEditorState()
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted != null) {
            val length = extracted.text?.length ?: 0
            if (length > 0) {
                ic.setSelection(0, length)
                ic.commitText("", 1)
            }
        } else {
            ic.performContextMenuAction(android.R.id.selectAll)
            ic.commitText("", 1)
        }
        enableAutoShift()
        refreshSuggestion()
    }

    private fun refreshSuggestion() {
        if (!::suggestionButton.isInitialized) return
        val beforeCursor = currentInputConnection
            ?.getTextBeforeCursor(256, 0)
            ?.toString()
            .orEmpty()

        activeSuggestion = suggestionProviders.firstNotNullOfOrNull { provider ->
            provider.suggest(beforeCursor)
        }

        val suggestion = activeSuggestion
        suggestionButton.apply {
            if (suggestion == null) {
                text = ""
                visibility = View.GONE
            } else {
                text = suggestion.text
                visibility = View.VISIBLE
            }
        }
    }

    private fun pasteFast() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val item = clipboard.primaryClip?.getItemAt(0) ?: return
        val text = item.coerceToText(this)?.toString() ?: return
        pasteText(text)
    }

    private fun pasteText(text: String) {
        val ic = currentInputConnection ?: return
        rememberEditorState()
        ic.beginBatchEdit()
        try {
            text.chunked(PASTE_CHUNK_SIZE).forEach { chunk -> ic.commitText(chunk, 1) }
        } finally {
            ic.endBatchEdit()
        }
        refreshSuggestion()
    }

    private fun performContextAction(actionId: Int) {
        currentInputConnection?.performContextMenuAction(actionId)
        refreshSuggestion()
    }

    private fun rememberEditorState() {
        if (restoringEditorHistory) return
        val snapshot = currentEditorSnapshot() ?: return
        if (undoStack.lastOrNull() != snapshot) {
            undoStack.addLast(snapshot)
            while (undoStack.size > EDITOR_HISTORY_LIMIT) undoStack.removeFirst()
        }
        redoStack.clear()
    }

    private fun currentEditorSnapshot(): EditorSnapshot? {
        val extracted = currentInputConnection
            ?.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val text = extracted.text?.toString() ?: return null
        if (text.length > EDITOR_HISTORY_TEXT_LIMIT) return null
        return EditorSnapshot(text, extracted.selectionStart, extracted.selectionEnd)
    }

    private fun restoreEditorSnapshot(snapshot: EditorSnapshot) {
        val ic = currentInputConnection ?: return
        val current = currentEditorSnapshot() ?: return
        restoringEditorHistory = true
        try {
            val oldText = current.text
            val newText = snapshot.text

            // Restore only the changed span. Replacing the whole editor contents made some
            // editors reinterpret embedded newlines / submit boundaries and produced phantom Enters.
            var prefix = 0
            val commonLimit = minOf(oldText.length, newText.length)
            while (prefix < commonLimit && oldText[prefix] == newText[prefix]) prefix++

            var suffix = 0
            val oldRemaining = oldText.length - prefix
            val newRemaining = newText.length - prefix
            while (
                suffix < oldRemaining &&
                suffix < newRemaining &&
                oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
            ) {
                suffix++
            }

            val oldEnd = oldText.length - suffix
            val newEnd = newText.length - suffix
            val replacement = newText.substring(prefix, newEnd)

            ic.beginBatchEdit()
            ic.setSelection(prefix, oldEnd)
            ic.commitText(replacement, 1)

            val max = newText.length
            ic.setSelection(
                snapshot.selectionStart.coerceIn(0, max),
                snapshot.selectionEnd.coerceIn(0, max),
            )
            ic.endBatchEdit()
        } finally {
            restoringEditorHistory = false
        }
        syncAutoShiftFromCursor()
        refreshSuggestion()
    }

    private fun performEditorHistory(undo: Boolean) {
        val ic = currentInputConnection ?: return
        val current = currentEditorSnapshot()
        val source = if (undo) undoStack else redoStack
        val destination = if (undo) redoStack else undoStack

        if (current != null && source.isNotEmpty()) {
            val target = source.removeLast()
            destination.addLast(current)
            restoreEditorSnapshot(target)
            return
        }

        // Best-effort fallback for edits that happened outside InlineIME. Some Android editors
        // don't expose undo/redo through InputConnection at all, so this is intentionally second.
        val nativeAction = if (undo) android.R.id.undo else android.R.id.redo
        ic.performContextMenuAction(nativeAction)
        syncAutoShiftFromCursor()
        refreshSuggestion()
    }

    private fun sendNavigation(keyCode: Int, useSelectionMeta: Boolean = true) {
        val ic = currentInputConnection ?: return
        if (selectionMode && useSelectionMeta) {
            sendSelectionNavigation(ic, keyCode)
        } else {
            sendPlainNavigation(ic, keyCode)
        }
        refreshSuggestion()
    }

    private fun sendPlainNavigation(ic: InputConnection, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0))
    }

    private fun sendSelectionNavigation(ic: InputConnection, keyCode: Int) {
        val anchor = selectionAnchor ?: currentSelectionStart(ic).also { selectionAnchor = it }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_SHIFT_ON
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))

        // Keep our anchor stable while the editor itself decides visual-line movement.
        selectionAnchor = anchor
    }

    private fun moveToBoundary(toEnd: Boolean) {
        val ic = currentInputConnection ?: return
        if (selectionMode) {
            val keyCode = if (toEnd) KeyEvent.KEYCODE_MOVE_END else KeyEvent.KEYCODE_MOVE_HOME
            sendSelectionNavigation(ic, keyCode)
        } else {
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            if (extracted != null) {
                val target = if (toEnd) extracted.text?.length ?: 0 else 0
                ic.setSelection(target, target)
            } else {
                val keyCode = if (toEnd) KeyEvent.KEYCODE_MOVE_END else KeyEvent.KEYCODE_MOVE_HOME
                sendPlainNavigation(ic, keyCode)
            }
        }
        refreshSuggestion()
    }

    private fun currentSelectionStart(ic: InputConnection): Int =
        ic.getExtractedText(ExtractedTextRequest(), 0)?.selectionStart ?: 0

    private fun keyView(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(COLOR_KEY_TEXT)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        background = insetKeyBackground(COLOR_KEY)
        isClickable = true
        isFocusable = true
    }

    private fun specialKey(label: String): TextView =
        keyView(label).apply {
            background = insetKeyBackground(COLOR_SPECIAL_KEY)
            textSize = 21f
        }

    private fun bottomKey(label: String, action: () -> Unit): TextView =
        specialKey(label).apply {
            setOnClickListener { action() }
            textSize = if (label.length > 3) 16f else 19f
        }

    private fun toolbarButton(label: String): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(COLOR_TOOLBAR_TEXT)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        isClickable = true
        isFocusable = true
    }

    private fun suggestionView(): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(COLOR_KEY_TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = roundedBackground(COLOR_SUGGESTION)
        isClickable = true
        isFocusable = true
    }

    private fun updateSelectionButton() {
        selectButton.apply {
            text = "T"
            background = if (selectionMode) {
                roundedBackground(COLOR_ACTIVE)
            } else {
                null
            }
            setTextColor(if (selectionMode) Color.WHITE else COLOR_TOOLBAR_TEXT)
        }
    }

    private fun updateShiftButton() {
        shiftButton.background = if (shift) {
            roundedBackground(COLOR_ACTIVE)
        } else {
            roundedBackground(COLOR_SPECIAL_KEY)
        }
        shiftButton.setTextColor(if (shift) Color.WHITE else COLOR_KEY_TEXT)
    }

    private fun roundedBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(8).toFloat()
            setColor(color)
        }

    private fun insetKeyBackground(color: Int): android.graphics.drawable.InsetDrawable =
        android.graphics.drawable.InsetDrawable(
            roundedBackground(color),
            dp(1),
            dp(2),
            dp(1),
            dp(2),
        )

    private fun weightedKeyParams(weight: Float, height: Int) =
        LinearLayout.LayoutParams(0, height, weight)

    private fun rowParams(height: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
            setMargins(dp(3), dp(2), dp(3), dp(2))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PASTE_CHUNK_SIZE = 8 * 1024
        const val CLIPBOARD_HISTORY_LIMIT = 50
        const val CLIPBOARD_PREVIEW_LENGTH = 120
        const val CLIPBOARD_HISTORY_ENTRY_LIMIT = 256 * 1024
        const val EDITOR_HISTORY_LIMIT = 40
        const val EDITOR_HISTORY_TEXT_LIMIT = 100 * 1024
        const val DOUBLE_TAP_MS = 500L
        const val DELETE_REPEAT_START_MS = 350L
        const val DELETE_CLEAR_POPUP_MS = 450L
        const val DELETE_FAST_MODE_MS = 1000L
        const val DELETE_WORD_MODE_MS = 1800L
        const val DELETE_INITIAL_INTERVAL_MS = 120L
        const val DELETE_FAST_INTERVAL_MS = 65L
        const val DELETE_WORD_INTERVAL_MS = 140L

        const val COLOR_BACKGROUND = 0xFF1B1C21.toInt()
        const val COLOR_KEY = 0xFF2B2C31.toInt()
        const val COLOR_SPECIAL_KEY = 0xFF35363D.toInt()
        const val COLOR_SUGGESTION = 0xFF2D2E34.toInt()
        const val COLOR_KEY_TEXT = 0xFFF1F1F5.toInt()
        const val COLOR_TOOLBAR_TEXT = 0xFFE7E7ED.toInt()
        const val COLOR_ACTIVE = 0xFF5C6BC0.toInt()
    }
}
