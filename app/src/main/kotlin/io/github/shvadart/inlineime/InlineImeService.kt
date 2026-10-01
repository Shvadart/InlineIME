package io.github.shvadart.inlineime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputConnection
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import io.github.shvadart.inlineime.suggestion.CalculatorSuggestionProvider
import io.github.shvadart.inlineime.suggestion.Suggestion
import io.github.shvadart.inlineime.suggestion.SuggestionProvider

class InlineImeService : InputMethodService() {
    private enum class Language { RU, EN }

    private var language = Language.RU
    private var shift = false
    private var selectionMode = false

    private lateinit var lettersContainer: LinearLayout
    private lateinit var suggestionButton: Button
    private lateinit var selectButton: Button
    private lateinit var languageButton: Button
    private lateinit var shiftButton: Button

    private var activeSuggestion: Suggestion? = null

    private val suggestionProviders: List<SuggestionProvider> = listOf(
        CalculatorSuggestionProvider(),
    )

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(6))
            setBackgroundColor(0xFF1C1C22.toInt())
        }

        root.addView(buildEditingToolbar())

        suggestionButton = keyButton("No suggestion").apply {
            isEnabled = false
            alpha = 0.45f
            setOnClickListener {
                val suggestion = activeSuggestion ?: return@setOnClickListener
                currentInputConnection?.commitText(suggestion.text, 1)
                refreshSuggestion()
            }
        }
        root.addView(suggestionButton, rowParams(dp(44)))

        root.addView(buildRow("1234567890".map { it.toString() }, ::commitTextKey))

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
            row.addView(keyButton(label).apply { setOnClickListener { action() } })
        }

        add("⏮") { sendNavigation(KeyEvent.KEYCODE_MOVE_HOME) }
        add("ALL") { performContextAction(android.R.id.selectAll) }
        add("↑") { sendNavigation(KeyEvent.KEYCODE_DPAD_UP) }
        add("←") { sendNavigation(KeyEvent.KEYCODE_DPAD_LEFT) }

        selectButton = keyButton("SEL").apply {
            setOnClickListener {
                selectionMode = !selectionMode
                text = if (selectionMode) "SEL*" else "SEL"
            }
        }
        row.addView(selectButton)

        add("→") { sendNavigation(KeyEvent.KEYCODE_DPAD_RIGHT) }
        add("↓") { sendNavigation(KeyEvent.KEYCODE_DPAD_DOWN) }
        add("CUT") { performContextAction(android.R.id.cut) }
        add("COPY") { performContextAction(android.R.id.copy) }
        add("PASTE") { pasteFast() }
        add("⏭") { sendNavigation(KeyEvent.KEYCODE_MOVE_END) }

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun buildBottomRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        shiftButton = keyButton("⇧").apply {
            setOnClickListener {
                shift = !shift
                renderLetterRows()
            }
        }
        row.addView(shiftButton, weightedKeyParams())

        row.addView(keyButton(",").apply {
            setOnClickListener { commitTextKey(",") }
        }, weightedKeyParams())

        languageButton = keyButton("RU").apply {
            setOnClickListener {
                language = if (language == Language.RU) Language.EN else Language.RU
                text = if (language == Language.RU) "RU" else "EN"
                renderLetterRows()
            }
        }
        row.addView(languageButton, weightedKeyParams())

        row.addView(keyButton("space").apply {
            setOnClickListener { commitTextKey(" ") }
        }, LinearLayout.LayoutParams(0, dp(54), 3f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })

        row.addView(keyButton(".").apply {
            setOnClickListener { commitTextKey(".") }
        }, weightedKeyParams())

        row.addView(keyButton("⌫").apply {
            setOnClickListener {
                currentInputConnection?.deleteSurroundingText(1, 0)
                refreshSuggestion()
            }
            setOnLongClickListener {
                currentInputConnection?.deleteSurroundingText(5, 0)
                refreshSuggestion()
                true
            }
        }, weightedKeyParams())

        row.addView(keyButton("↵").apply {
            setOnClickListener {
                sendNavigation(KeyEvent.KEYCODE_ENTER, useSelectionMeta = false)
                refreshSuggestion()
            }
        }, weightedKeyParams())

        return row
    }

    private fun renderLetterRows() {
        if (!::lettersContainer.isInitialized) return
        lettersContainer.removeAllViews()

        val rows = when (language) {
            Language.RU -> listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
            Language.EN -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        }

        rows.forEach { chars ->
            val labels = chars.map { char ->
                val c = if (shift) char.uppercaseChar() else char
                c.toString()
            }
            lettersContainer.addView(buildRow(labels, ::commitLetter))
        }

        if (::shiftButton.isInitialized) {
            shiftButton.text = if (shift) "⇧*" else "⇧"
        }
    }

    private fun buildRow(labels: List<String>, action: (String) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        labels.forEach { label ->
            row.addView(keyButton(label).apply {
                setOnClickListener { action(label) }
            }, weightedKeyParams())
        }
        return row
    }

    private fun commitLetter(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (shift) {
            shift = false
            renderLetterRows()
        }
        refreshSuggestion()
    }

    private fun commitTextKey(text: String) {
        currentInputConnection?.commitText(text, 1)
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

        suggestionButton.apply {
            val suggestion = activeSuggestion
            if (suggestion == null) {
                text = "No suggestion"
                isEnabled = false
                alpha = 0.45f
            } else {
                text = suggestion.text
                isEnabled = true
                alpha = 1f
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
        val now = SystemClock.uptimeMillis()
        val meta = if (selectionMode && useSelectionMeta) KeyEvent.META_SHIFT_ON else 0

        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
        refreshSuggestion()
    }

    private fun keyButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun weightedKeyParams() =
        LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun rowParams(height: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PASTE_CHUNK_SIZE = 8 * 1024
    }
}
