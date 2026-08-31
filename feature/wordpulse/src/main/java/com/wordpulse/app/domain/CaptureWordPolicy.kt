package com.wordpulse.app.domain

object CaptureWordPolicy {
    const val VERSION = "ascii_lowercase_v1"

    fun isValidFieldText(value: String): Boolean =
        value.all(::isAllowedCharacter)

    fun isValidWord(value: String): Boolean =
        value.isNotEmpty() && isValidFieldText(value)

    fun validateFieldText(value: String): CaptureInputValidation =
        if (isValidFieldText(value)) {
            CaptureInputValidation.Valid
        } else {
            CaptureInputValidation.Invalid(classifyInvalidInput(value))
        }

    fun isValidLegacyImportWord(value: String): Boolean =
        value.trim().isNotEmpty()

    private fun isAllowedCharacter(char: Char): Boolean =
        char in 'a'..'z'

    private fun classifyInvalidInput(value: String): InvalidInputReason =
        when {
            value.any { it in 'A'..'Z' } -> InvalidInputReason.Uppercase
            value.any(Char::isWhitespace) -> InvalidInputReason.Whitespace
            value.any(Char::isDigit) -> InvalidInputReason.Number
            value.any { it.code > 0x7f } -> InvalidInputReason.NonAscii
            else -> InvalidInputReason.Symbol
        }
}

sealed interface CaptureInputValidation {
    data object Valid : CaptureInputValidation

    data class Invalid(val reason: InvalidInputReason) : CaptureInputValidation
}

enum class InvalidInputReason {
    Uppercase,
    Whitespace,
    Number,
    Symbol,
    NonAscii,
}
