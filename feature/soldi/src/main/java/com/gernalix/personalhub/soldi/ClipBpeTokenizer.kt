package com.gernalix.personalhub.soldi

import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/** Minimal CLIP byte-level BPE reader for the pinned TinyCLIP tokenizer.json. */
internal class ClipBpeTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val mergeRanks: Map<String, Int>,
) {
    private val cache = HashMap<String, List<String>>()
    private val byteEncoder = byteEncoder()
    private val tokenPattern = Regex(
        """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
    )

    fun encode(text: String, maxLength: Int = 77): LongArray {
        require(maxLength >= 2)
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
        val ids = ArrayList<Long>()
        ids += BOS.toLong()
        tokenPattern.findAll(normalized).forEach { match ->
            val encoded = buildString {
                match.value.encodeToByteArray().forEach { byte ->
                    append(byteEncoder[byte.toInt() and 0xff])
                }
            }
            bpe(encoded).forEach { piece -> ids += (vocab[piece] ?: EOS).toLong() }
        }
        ids += EOS.toLong()
        if (ids.size > maxLength) {
            while (ids.size > maxLength) ids.removeAt(ids.lastIndex)
            ids[ids.lastIndex] = EOS.toLong()
        }
        return ids.toLongArray()
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()
        var word = token.map { it.toString() }.toMutableList()
        word[word.lastIndex] = word.last() + "</w>"
        while (word.size > 1) {
            var bestIndex = -1
            var bestRank = Int.MAX_VALUE
            for (index in 0 until word.lastIndex) {
                val rank = mergeRanks[word[index] + " " + word[index + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = index
                }
            }
            if (bestIndex < 0) break
            val left = word[bestIndex]
            val right = word[bestIndex + 1]
            val merged = mutableListOf<String>()
            var index = 0
            while (index < word.size) {
                if (index < word.lastIndex && word[index] == left && word[index + 1] == right) {
                    merged += left + right
                    index += 2
                } else {
                    merged += word[index]
                    index += 1
                }
            }
            word = merged
        }
        return word.toList().also { cache[token] = it }
    }

    companion object {
        const val BOS = 49406
        const val EOS = 49407

        fun fromJson(json: String): ClipBpeTokenizer {
            val root = JSONObject(json)
            val model = root.getJSONObject("model")
            val vocabJson = model.getJSONObject("vocab")
            val vocab = buildMap {
                vocabJson.keys().forEach { token -> put(token, vocabJson.getInt(token)) }
            }
            val mergesJson = model.getJSONArray("merges")
            val ranks = buildMap {
                for (index in 0 until mergesJson.length()) {
                    val pair = mergesJson.getJSONArray(index)
                    put(pair.getString(0) + " " + pair.getString(1), index)
                }
            }
            return ClipBpeTokenizer(vocab, ranks)
        }

        private fun byteEncoder(): Map<Int, String> {
            val base = mutableListOf<Int>()
            base += (33..126)
            base += (161..172)
            base += (174..255)
            val bytes = base.toMutableList()
            val codePoints = base.toMutableList()
            var extra = 0
            for (value in 0..255) {
                if (value !in base) {
                    bytes += value
                    codePoints += 256 + extra
                    extra += 1
                }
            }
            return bytes.indices.associate { index ->
                bytes[index] to String(Character.toChars(codePoints[index]))
            }
        }
    }
}
