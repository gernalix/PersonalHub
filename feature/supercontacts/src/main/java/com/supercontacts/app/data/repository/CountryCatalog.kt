package com.supercontacts.app.data.repository

import android.content.Context
import java.util.Locale

data class CountryOption(
    val code: String,
    val countryEn: String,
    val nationalityEn: String,
    val countryIt: String,
    val nationalityIt: String,
) {
    fun nationality(locale: Locale = Locale.getDefault()): String =
        if (locale.language == "it") nationalityIt else nationalityEn

    fun country(locale: Locale = Locale.getDefault()): String =
        if (locale.language == "it") countryIt else countryEn
}

object CountryCatalog {
    private const val ASSET_NAME = "countries-v1.csv"

    fun load(context: Context): List<CountryOption> =
        context.assets.open(ASSET_NAME).bufferedReader().useLines { lines ->
            lines
                .drop(1)
                .mapNotNull(::parseLine)
                .toList()
        }

    fun flagEmoji(countryCode: String?): String {
        val code = countryCode?.trim()?.uppercase(Locale.ROOT)
        if (code == null || !code.matches(Regex("[A-Z]{2}"))) return ""
        val first = Character.codePointAt(code, 0) - 'A'.code + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 'A'.code + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private fun parseLine(line: String): CountryOption? {
        if (line.isBlank()) return null
        val parts = line.split(';')
        if (parts.size != 5) return null
        val code = parts[0].trim().uppercase(Locale.ROOT)
        if (!code.matches(Regex("[A-Z]{2}"))) return null
        return CountryOption(
            code = code,
            countryEn = parts[1].trim(),
            nationalityEn = parts[2].trim(),
            countryIt = parts[3].trim(),
            nationalityIt = parts[4].trim(),
        )
    }
}
