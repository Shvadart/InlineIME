package io.github.shvadart.inlineime.suggestion

enum class SuggestionKind {
    CALCULATOR,
    DICTIONARY,
    CLIPBOARD,
    AI,
}

data class Suggestion(
    val text: String,
    val kind: SuggestionKind,
)

fun interface SuggestionProvider {
    fun suggest(contextBeforeCursor: String): Suggestion?
}
