package io.github.shvadart.inlineime

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
import android.widget.TextView
import io.github.shvadart.inlineime.suggestion.CalculatorSuggestionProvider
import io.github.shvadart.inlineime.suggestion.Suggestion
import io.github.shvadart.inlineime.suggestion.SuggestionProvider

class InlineImeService : InputMethodService() {
    private enum class Language { RU, EN }

    private var language = Language.RU
    private var shift = true
    private var capsLock = false
    private var symbols = false
    private var selectionMode = false
    private var selectionAnchor: Int? = null
    private var lastShiftTap = 0L

    private lateinit var lettersContainer: LinearLayout
    private lateinit var suggestionButton: TextView
    private lateinit var selectButton: TextView
    private lateinit var languageButton: TextView
    private lateinit var shiftButton: TextView
    private lateinit var spaceButton: TextView

    private val gestureHandler = Handler(Looper.getMainLooper())
    private var clearDeleteArmed = false
    private var clearDeleteRunnable: Runnable? = null

    private var activeSuggestion: Suggestion? = null

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

        root.addView(
            buildEqualRow("1234567890".map { it.toString() }, ::commitTextKey, dp(48)),
        )

        lettersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(lettersContainer)

        root.addView(buildBottomRow())
        renderLetterRows()

        return root
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshSuggestion()
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

        add("|←") { sendNavigation(KeyEvent.KEYCODE_MOVE_HOME) }
        add("▣") { performContextAction(android.R.id.selectAll) }
        add("↑") { sendNavigation(KeyEvent.KEYCODE_DPAD_UP) }
        add("←") { sendNavigation(KeyEvent.KEYCODE_DPAD_LEFT) }

        selectButton = toolbarButton("T").apply {
            setOnClickListener {
                selectionMode = !selectionMode
                updateSelectionButton()
            }
        }
        row.addView(selectButton, LinearLayout.LayoutParams(0, dp(40), 1f))

        add("→") { sendNavigation(KeyEvent.KEYCODE_DPAD_RIGHT) }
        add("↓") { sendNavigation(KeyEvent.KEYCODE_DPAD_DOWN) }
        add("⧉") { performContextAction(android.R.id.copy) }
        add("▤") { pasteFast() }
        add("→|") { sendNavigation(KeyEvent.KEYCODE_MOVE_END) }

        return row
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
            currentInputConnection?.commitText("\n", 1)
            enableAutoShift()
            refreshSuggestion()
        }, weightedKeyParams(1.2f, dp(56)))

        return row
    }

    private fun renderLetterRows() {
        if (!::lettersContainer.isInitialized) return
        lettersContainer.removeAllViews()

        if (symbols) {
            listOf("!@#%&*+-=", "()[]{}<>", "\\/:;\"'€£¥").forEach { chars ->
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
                            clearDeleteArmed = false
                            clearDeleteRunnable?.let(gestureHandler::removeCallbacks)
                            clearDeleteRunnable = Runnable {
                                clearDeleteArmed = true
                                (view as TextView).text = "✕"
                            }.also { gestureHandler.postDelayed(it, CLEAR_DELETE_HOLD_MS) }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            clearDeleteRunnable?.let(gestureHandler::removeCallbacks)
                            clearDeleteRunnable = null
                            if (clearDeleteArmed) clearAllText() else deleteOne()
                            clearDeleteArmed = false
                            (view as TextView).text = "⌫"
                            view.performClick()
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            clearDeleteRunnable?.let(gestureHandler::removeCallbacks)
                            clearDeleteRunnable = null
                            clearDeleteArmed = false
                            (view as TextView).text = "⌫"
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

    private fun deleteOne() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        refreshSuggestion()
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
        currentInputConnection?.commitText(text, 1)
        if (shift && !capsLock) {
            shift = false
            renderLetterRows()
        }
        refreshSuggestion()
    }

    private fun commitTextKey(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (text == "\n" || text == "." || text == "!" || text == "?") enableAutoShift()
        refreshSuggestion()
    }

    private fun enableAutoShift() {
        if (!capsLock && !shift) {
            shift = true
            renderLetterRows()
        }
    }

    private fun buildNumberRow(): View =
        buildEqualRow("1234567890".map { it.toString() }, ::commitTextKey, dp(48))

    private fun clearAllText() {
        val ic = currentInputConnection ?: return
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
        val ic = currentInputConnection ?: return

        if (ic.performContextMenuAction(android.R.id.paste)) {
            refreshSuggestion()
            return
        }

        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val item = clipboard.primaryClip?.getItemAt(0) ?: return
        val text = item.coerceToText(this)?.toString() ?: return

        ic.beginBatchEdit()
        try {
            text.chunked(PASTE_CHUNK_SIZE).forEach { chunk ->
                ic.commitText(chunk, 1)
            }
        } finally {
            ic.endBatchEdit()
        }
        refreshSuggestion()
    }

    private fun performContextAction(actionId: Int) {
        currentInputConnection?.performContextMenuAction(actionId)
        refreshSuggestion()
    }

    private fun sendNavigation(keyCode: Int, useSelectionMeta: Boolean = true) {
        val ic = currentInputConnection ?: return
        if (selectionMode && useSelectionMeta &&
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
            val anchor = selectionAnchor ?: extracted.selectionStart.also { selectionAnchor = it }
            val active = if (extracted.selectionStart == anchor) extracted.selectionEnd else extracted.selectionStart
            val next = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> (active - 1).coerceAtLeast(0)
                else -> (active + 1).coerceAtMost(extracted.text.length)
            }
            ic.setSelection(minOf(anchor, next), maxOf(anchor, next))
        } else {
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0))
        }
        refreshSuggestion()
    }

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
        const val DOUBLE_TAP_MS = 500L
        const val CLEAR_DELETE_HOLD_MS = 650L

        const val COLOR_BACKGROUND = 0xFF1B1C21.toInt()
        const val COLOR_KEY = 0xFF2B2C31.toInt()
        const val COLOR_SPECIAL_KEY = 0xFF35363D.toInt()
        const val COLOR_SUGGESTION = 0xFF2D2E34.toInt()
        const val COLOR_KEY_TEXT = 0xFFF1F1F5.toInt()
        const val COLOR_TOOLBAR_TEXT = 0xFFE7E7ED.toInt()
        const val COLOR_ACTIVE = 0xFF5C6BC0.toInt()
    }
}
