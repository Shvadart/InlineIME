package io.github.shvadart.inlineime.suggestion

import java.math.BigDecimal
import java.math.MathContext

class CalculatorSuggestionProvider : SuggestionProvider {
    override fun suggest(contextBeforeCursor: String): Suggestion? {
        val match = EXPRESSION_AT_CURSOR.find(contextBeforeCursor) ?: return null
        val expression = match.groupValues[1].trim()
        if (expression.isBlank() || expression.length > 80) return null

        val normalized = expression
            .replace(',', '.')
            .replace('×', '*')
            .replace('÷', '/')

        val value = runCatching { Parser(normalized).parse() }.getOrNull() ?: return null
        return Suggestion(format(value), SuggestionKind.CALCULATOR)
    }

    private fun format(value: BigDecimal): String {
        val normalized = value.stripTrailingZeros()
        return if (normalized.scale() < 0) {
            normalized.setScale(0).toPlainString()
        } else {
            normalized.toPlainString()
        }
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): BigDecimal {
            val value = parseExpression()
            skipSpaces()
            require(index == source.length) { "Unexpected token" }
            return value
        }

        private fun parseExpression(): BigDecimal {
            var value = parseTerm()
            while (true) {
                skipSpaces()
                value = when {
                    consume('+') -> value.add(parseTerm(), MC)
                    consume('-') -> value.subtract(parseTerm(), MC)
                    else -> return value
                }
            }
        }

        private fun parseTerm(): BigDecimal {
            var value = parseFactor()
            while (true) {
                skipSpaces()
                value = when {
                    consume('*') -> value.multiply(parseFactor(), MC)
                    consume('/') -> {
                        val divisor = parseFactor()
                        require(divisor.compareTo(BigDecimal.ZERO) != 0) { "Division by zero" }
                        value.divide(divisor, MC)
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): BigDecimal {
            skipSpaces()

            if (consume('+')) return parseFactor()
            if (consume('-')) return parseFactor().negate(MC)

            if (consume('(')) {
                val value = parseExpression()
                skipSpaces()
                require(consume(')')) { "Missing closing parenthesis" }
                return value
            }

            return parseNumber()
        }

        private fun parseNumber(): BigDecimal {
            skipSpaces()
            val start = index
            var dotSeen = false

            while (index < source.length) {
                val c = source[index]
                when {
                    c.isDigit() -> index++
                    c == '.' && !dotSeen -> {
                        dotSeen = true
                        index++
                    }
                    else -> break
                }
            }

            require(index > start) { "Number expected" }
            return source.substring(start, index).toBigDecimal(MC)
        }

        private fun consume(expected: Char): Boolean {
            if (index < source.length && source[index] == expected) {
                index++
                return true
            }
            return false
        }

        private fun skipSpaces() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }

    private companion object {
        val MC: MathContext = MathContext.DECIMAL64
        val EXPRESSION_AT_CURSOR = Regex("""([0-9.,+\-*/×÷()\s]+)=\s*$""")
    }
}
