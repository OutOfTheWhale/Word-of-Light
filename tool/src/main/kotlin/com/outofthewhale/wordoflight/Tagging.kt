package com.outofthewhale.wordoflight

/**
 * One word of the text, with whatever the source knew about it.
 *
 * [strong] is a Strong's number like `"G2455"` or `"H7225"`. [added] marks a
 * word the KJV translators supplied that has no counterpart in the Hebrew or
 * Greek - printed in italics since 1611, and worth keeping visible.
 */
data class Word(
    val text: String,
    val strong: String? = null,
    val added: Boolean = false,
) {
    /** Text without trailing punctuation, for matching against other verses. */
    val bare: String get() = text.trim(*PUNCTUATION)

    private companion object {
        val PUNCTUATION = charArrayOf(
            ',', '.', ';', ':', '!', '?', '"', '\'', '(', ')', '[', ']',
            '‘', '’', '“', '”',
        )
    }
}

/**
 * Reads the inline tagging the importer preserves:
 *
 *     Jude,[G2455] the servant[G1401] ... <em>be</em> glory[G1391]
 *
 * Parsing happens per chapter as it is displayed rather than at import, which
 * keeps the bundled assets to the size of the text itself.
 */
object Tagging {

    private val TAG = Regex("""\[([GH])(\d+)]""")
    private val EM = Regex("""</?em>""")
    private val WHITESPACE = Regex("""\s+""")

    /** Drop all markup, leaving readable text. */
    fun strip(tagged: String): String =
        tagged.replace(TAG, "").replace(EM, "").replace(WHITESPACE, " ").trim()

    /**
     * Split into words, carrying each one's Strong's number.
     *
     * `<em>` can wrap a single word or run across several, so the italic state
     * is tracked across tokens rather than assumed to open and close within one.
     */
    fun parse(tagged: String): List<Word> {
        val words = mutableListOf<Word>()
        var added = false

        for (rawToken in tagged.split(WHITESPACE)) {
            if (rawToken.isBlank()) continue
            var token = rawToken

            val opens = token.contains("<em>")
            val closes = token.contains("</em>")
            if (opens) added = true
            token = token.replace(EM, "")

            val strong = TAG.find(token)?.let { "${it.groupValues[1]}${it.groupValues[2]}" }
            token = token.replace(TAG, "")

            if (token.isNotEmpty()) {
                words += Word(text = token, strong = strong, added = added)
            }
            if (closes) added = false
        }
        return words
    }

    /**
     * Whether a Strong's number belongs to the testament it was found in.
     *
     * The Old Testament is Hebrew and Aramaic, the New is Greek. A Greek number
     * on an Old Testament word means the data is wrong, and showing it would
     * repeat exactly the mistake the earlier prototype made.
     */
    fun isConsistent(strong: String, testament: Testament): Boolean = when {
        strong.startsWith("H") -> testament == Testament.OLD
        strong.startsWith("G") -> testament == Testament.NEW
        else -> false
    }
}
