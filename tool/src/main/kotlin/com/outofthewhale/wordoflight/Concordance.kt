package com.outofthewhale.wordoflight

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A single verse, as pointed to by a concordance lookup. */
data class VerseRef(
    val book: String,
    val chapter: Int,
    val verse: Int,
) {
    fun label(): String = "${Canon.book(book)?.name ?: book} $chapter:$verse"

    fun chapterRef(): ChapterRef = ChapterRef(book, chapter)

    companion object {
        /** Parses the `"gen.1.1"` form the index is stored in. */
        fun parse(raw: String): VerseRef? {
            val parts = raw.split('.')
            if (parts.size != 3) return null
            val chapter = parts[1].toIntOrNull() ?: return null
            val verse = parts[2].toIntOrNull() ?: return null
            return VerseRef(parts[0], chapter, verse)
        }
    }
}

/**
 * Every verse that uses a given Strong's number.
 *
 * This is what answers "found X verses" when a word is tapped. Working it out
 * on demand would mean scanning ~9 MB of text per tap, so `build_concordance.py`
 * precomputes it: 13,654 numbers across 291,919 references, bucketed by hundred
 * exactly like [Lexicon] so a lookup touches ~25 KB instead of 3.6 MB.
 *
 * Independent of the lexicon on purpose. About 1% of tagged words - mostly
 * proper names - have no dictionary entry, and those should still be able to
 * show you everywhere the name occurs.
 */
class Concordance(private val readAsset: (String) -> ByteArray) {

    private val json = Json { ignoreUnknownKeys = true }
    private val buckets = mutableMapOf<String, Map<String, List<String>>>()

    fun occurrences(strong: String): List<VerseRef> =
        references(strong).mapNotNull { VerseRef.parse(it) }

    /** Cheaper than [occurrences] when only the count is being shown. */
    fun count(strong: String): Int = references(strong).size

    private fun references(strong: String): List<String> {
        val name = bucketName(strong) ?: return emptyList()
        return bucket(name)[strong].orEmpty()
    }

    private fun bucketName(strong: String): String? {
        if (strong.length < 2) return null
        val prefix = strong.first()
        if (prefix != 'G' && prefix != 'H') return null
        val number = strong.drop(1).toIntOrNull() ?: return null
        return "${prefix.lowercaseChar()}${number / BUCKET_SIZE}"
    }

    private fun bucket(name: String): Map<String, List<String>> =
        buckets.getOrPut(name) {
            try {
                json.decodeFromString(
                    MapSerializer(String.serializer(), ListSerializer(String.serializer())),
                    readAsset("$ASSETS_DIR/$name.json").decodeToString(),
                )
            } catch (e: Exception) {
                emptyMap()
            }
        }

    private companion object {
        const val BUCKET_SIZE = 100
        const val ASSETS_DIR = "concordance"
    }
}
