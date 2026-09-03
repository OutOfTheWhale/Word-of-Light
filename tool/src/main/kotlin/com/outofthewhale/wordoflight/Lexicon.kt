package com.outofthewhale.wordoflight

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One Strong's entry: the original word and what it means. */
@Serializable
data class StrongsEntry(
    val word: String = "",
    val translit: String = "",
    val definition: String = "",
    val pos: String = "",
    val root: String = "",
    val usage: String = "",
) {
    val isEmpty: Boolean get() = word.isBlank() && definition.isBlank()
}

/**
 * Strong's Hebrew and Greek dictionaries, looked up one entry at a time.
 *
 * The full lexicon is ~6 MB across 12,040 entries. Parsing that to answer a
 * single tapped word would stall the screen, so the importer splits it into
 * buckets of a hundred - `G2455` lives in `lexicon/g24.json`, about 50 KB.
 * Buckets are parsed on first use and kept, so a word study stays in one or
 * two of them.
 */
class Lexicon(private val readAsset: (String) -> ByteArray) {

    private val json = Json { ignoreUnknownKeys = true }
    private val buckets = mutableMapOf<String, Map<String, StrongsEntry>>()

    /** Null when the number is malformed or absent from the lexicon. */
    fun lookup(strong: String): StrongsEntry? {
        val name = bucketName(strong) ?: return null
        return bucket(name)[strong]
    }

    fun has(strong: String): Boolean = lookup(strong) != null

    private fun bucketName(strong: String): String? {
        if (strong.length < 2) return null
        val prefix = strong.first()
        if (prefix != 'G' && prefix != 'H') return null
        val number = strong.drop(1).toIntOrNull() ?: return null
        return "${prefix.lowercaseChar()}${number / BUCKET_SIZE}"
    }

    private fun bucket(name: String): Map<String, StrongsEntry> =
        buckets.getOrPut(name) {
            try {
                val bytes = readAsset("$ASSETS_DIR/$name.json")
                json.decodeFromString(
                    MapSerializer(String.serializer(), StrongsEntry.serializer()),
                    bytes.decodeToString(),
                )
            } catch (e: Exception) {
                // A missing bucket means no entry for those numbers, which is
                // normal - Strong's numbering has gaps.
                emptyMap()
            }
        }

    private companion object {
        const val BUCKET_SIZE = 100
        const val ASSETS_DIR = "lexicon"
    }
}
