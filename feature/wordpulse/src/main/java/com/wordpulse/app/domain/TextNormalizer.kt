package com.wordpulse.app.domain

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {
    fun normalize(input: String): String =
        Normalizer.normalize(input.trim(), Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
}
