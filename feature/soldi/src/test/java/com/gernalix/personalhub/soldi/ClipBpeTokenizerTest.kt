package com.gernalix.personalhub.soldi

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipBpeTokenizerTest {
    @Test
    fun appliesLowercaseWhitespaceByteBpeAndSpecialTokens() {
        val tokenizer = ClipBpeTokenizer.fromJson(
            """
            {
              "model": {
                "vocab": {
                  "<|startoftext|>": 49406,
                  "<|endoftext|>": 49407,
                  "r": 1,
                  "e": 2,
                  "d</w>": 3,
                  "re": 4,
                  "red</w>": 736
                },
                "merges": [["r","e"],["re","d</w>"]]
              }
            }
            """.trimIndent(),
        )
        assertArrayEquals(
            longArrayOf(49406, 736, 49407),
            tokenizer.encode("  RED  "),
        )
    }

    @Test
    fun truncationAlwaysKeepsEndToken() {
        val tokenizer = ClipBpeTokenizer.fromJson(
            """
            {
              "model": {
                "vocab": {
                  "<|startoftext|>": 49406,
                  "<|endoftext|>": 49407,
                  "a</w>": 10
                },
                "merges": []
              }
            }
            """.trimIndent(),
        )
        assertArrayEquals(
            longArrayOf(49406, 10, 49407),
            tokenizer.encode("a a a a", maxLength = 3),
        )
    }
}
