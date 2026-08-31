package com.supercontacts.app.data.repository

object PhoneNormalizer {
    fun normalize(value: String): String =
        value.filter { it.isDigit() }
}
