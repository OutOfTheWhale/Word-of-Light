package com.outofthewhale.wordoflight

/**
 * Splits a chapter of plain text from a publisher API into verses.
 *
 * The APIs return a chapter as one run of text with verse numbers inline,
 * either bracketed (`[1] In the beginning`) or bare (`1 In the beginning`)
 * depending on the provider and its options. Both are handled.
 *
 * The hard part is telling a verse number from a number in the text. A
 * genealogy reads "Adam was 130 years old ... 4 Adam lived 800 years", and
 * splitting on every integer shreds it. So only the *next expected* number can
 * open a verse: after verse 3 nothing but a standalone 4 will do, and 130 and
 * 800 are left alone. This mirrors the importer in tools/import.
 *
 * A bracketed number is unambiguous and is trusted directly.
 */
object ChapterParser {

    private val BRACKETED = Regex("""\[(\d+)]""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * Punctuation left stranded by markup that was stripped out.
     *
     * Asking for text without notes removes the note bodies but can leave
     * their separators behind - CSB Genesis 1:1 comes back ending "the
     * earth. ," where the cross references used to be. In English typography a
     * comma, semicolon or colon never has whitespace before it, so one that
     * does is residue rather than something the translators wrote.
     */
    private val ORPHANED_PUNCTUATION = Regex("""\s+([,;:])(?=\s|$)""")

    /**
     * Verses in order. Empty when nothing could be recognised, which the caller
     * should treat as a failed fetch rather than an empty chapter.
     */
    fun parse(raw: String, chapter: Int): List<Verse> {
        val text = raw.replace(' ', ' ').trim()
        if (text.isEmpty()) return emptyList()

        return if (BRACKETED.containsMatchIn(text)) {
            parseBracketed(text, chapter)
        } else {
            parseSequential(text, chapter)
        }
    }

    /** `[1] text [2] text` - the number is explicit, so trust it. */
    private fun parseBracketed(text: String, chapter: Int): List<Verse> {
        val matches = BRACKETED.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()

        val verses = mutableListOf<Verse>()
        matches.forEachIndexed { index, match ->
            val number = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else text.length
            val body = clean(text.substring(start, end))
            if (body.isNotEmpty()) verses += verse(chapter, number, body)
        }
        return verses
    }

    /** `1 text 2 text` - only the next expected number may open a verse. */
    private fun parseSequential(text: String, chapter: Int): List<Verse> {
        val verses = mutableListOf<Verse>()
        var current = 1
        var cursor = 0

        // A chapter may open without a "1" at all; the text before the first
        // number found is verse 1 either way.
        val first = findNumber(text, 1, 0)
        if (first != null && text.substring(0, first.first).isBlank()) {
            cursor = first.last + 1
        }

        while (true) {
            val next = findNumber(text, current + 1, cursor)
            val body = clean(text.substring(cursor, next?.first ?: text.length))
            if (body.isNotEmpty()) verses += verse(chapter, current, body)
            if (next == null) break
            current += 1
            cursor = next.last + 1
        }
        return verses
    }

    /** The position of [want] as a standalone token, at or after [from]. */
    private fun findNumber(text: String, want: Int, from: Int): IntRange? {
        val pattern = Regex("""(?<!\d)$want(?!\d)""")
        for (match in pattern.findAll(text)) {
            if (match.range.first < from) continue
            val before = text.getOrNull(match.range.first - 1)
            val after = text.getOrNull(match.range.last + 1)
            if (before != null && !before.isWhitespace()) continue
            if (after != null && !after.isWhitespace() && after != '“' && after != '"') continue
            return match.range
        }
        return null
    }

    private fun clean(fragment: String) = fragment
        .replace(WHITESPACE, " ")
        .replace(ORPHANED_PUNCTUATION, "")
        .trim()

    private fun verse(chapter: Int, number: Int, body: String) = Verse(
        chapter = chapter,
        verse = number,
        heading = null,
        lines = listOf(VerseLine(text = body)),
    )
}
