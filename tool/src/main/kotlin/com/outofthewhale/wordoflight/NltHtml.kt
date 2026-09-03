package com.outofthewhale.wordoflight

/**
 * Turns Tyndale's HTML into verses.
 *
 * Separate from [NltApi] so it can be tested without a network or a key, the
 * same way [ChapterParser] is separate from [BibleApi].
 *
 * The shape is not documented; this is written against what the API actually
 * returns. A chapter arrives as a whole HTML document, with each verse marked
 * three redundant ways - a `verse_export` wrapper, a `vn` attribute on it, and
 * a `<span class="vn">`. All three are 1:1 with the verse, so matching more
 * than one splits every verse twice.
 */
object NltHtml {

    fun parse(html: String, chapter: Int): List<Verse> {
        val verses = parseVerseSpans(html, chapter)
        // If the markup ever changes shape, fall back to reading it as plain
        // text rather than returning nothing.
        return verses.ifEmpty { ChapterParser.parse(stripTags(html), chapter) }
    }

    /**
     * Splits on the verse-number spans.
     *
     * The span is the marker to use rather than the wrapper: consuming it keeps
     * the verse digit out of the body, and everything before the first one -
     * document head, chapter heading - falls away for free.
     */
    private fun parseVerseSpans(html: String, chapter: Int): List<Verse> {
        val matches = VERSE_NUMBER.findAll(html).toList()
        if (matches.isEmpty()) return emptyList()

        val verses = mutableListOf<Verse>()

        // A subhead introduces the verse that follows it, so it sits at the end
        // of the previous verse's fragment - or, at the top of a chapter,
        // before the first marker altogether. Either way it is carried forward
        // to the verse it belongs to rather than kept where it was found.
        var pendingHeading = subheadIn(html.substring(0, matches.first().range.first))

        matches.forEachIndexed { index, match ->
            val number = match.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else html.length
            val fragment = html.substring(start, end)

            val heading = pendingHeading
            pendingHeading = subheadIn(fragment)

            val body = stripTags(fragment.replace(SUBHEAD, " "))
            if (body.isNotEmpty()) {
                verses += Verse(
                    chapter = chapter,
                    verse = number,
                    heading = heading,
                    lines = listOf(VerseLine(text = body)),
                )
            }
        }
        return verses
    }

    /** The NLT carries section headings that no public-domain KJV can. */
    private fun subheadIn(fragment: String): String? = SUBHEAD.find(fragment)
        ?.let { stripTags(it.groupValues[1]) }
        ?.takeIf { it.isNotEmpty() }

    /**
     * Removes translator notes along with everything inside them.
     *
     * Done by tag balance rather than by pattern. A note is not flat - it holds
     * a `tn-ref` and whatever formatting the note text uses - so a regex whose
     * body must be tag-free never matches the outer element, and peeling from
     * the inside out stalls as soon as it meets a tag it has no rule for.
     * Counting opens and closes works whatever the note happens to contain.
     */
    internal fun removeNotes(html: String): String =
        NOTE_TAGS.fold(html) { current, tag -> removeTagged(current, tag) }

    private fun removeTagged(html: String, tag: String): String {
        val opening = Regex("""<$tag[^>]*class="(?:$NOTE_CLASSES)"[^>]*>""", RegexOption.IGNORE_CASE)
        val builder = StringBuilder(html.length)
        var index = 0
        while (true) {
            val match = opening.find(html, index) ?: break
            builder.append(html, index, match.range.first).append(' ')
            index = endOfElement(html, tag, match.range.last + 1)
        }
        return builder.append(html, index, html.length).toString()
    }

    /** Index just past the closing tag that balances an already-open element. */
    private fun endOfElement(html: String, tag: String, from: Int): Int {
        val token = Regex("""<(/?)$tag\b[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 1
        var cursor = from
        while (depth > 0) {
            val match = token.find(html, cursor) ?: return html.length
            depth += if (match.groupValues[1] == "/") -1 else 1
            cursor = match.range.last + 1
        }
        return cursor
    }

    internal fun stripTags(html: String): String =
        removeNotes(html.replace(SCRIPT_OR_STYLE, " "))
            .replace(HTML_TAG, " ")
            .let(::unescape)
            .replace(WHITESPACE, " ")
            // Same rule as ChapterParser: punctuation stranded by removed
            // markup. A comma never has whitespace before it.
            .replace(ORPHANED_PUNCTUATION, "")
            .trim()

    private fun unescape(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(NUMERIC_ENTITY) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        // Last, so that an escaped ampersand cannot revive a second entity.
        .replace("&amp;", "&")

    /**
     * The verse-number span. Matched on the exact class value rather than a
     * word boundary: `ext-poet1-vn-sp` also contains "vn", and a looser pattern
     * would treat poetry spans as verse markers.
     */
    private val VERSE_NUMBER = Regex(
        """<span[^>]*class="vn"[^>]*>\s*(\d+)\s*</span>""",
        RegexOption.IGNORE_CASE,
    )

    private val SUBHEAD = Regex(
        """<h\d[^>]*class="subhead"[^>]*>([\s\S]*?)</h\d>""",
        RegexOption.IGNORE_CASE,
    )

    /** Translator notes: the `tn` body, its `tn-ref`, and the `a-tn` marker. */
    private const val NOTE_CLASSES = "tn|tn-ref|a-tn"
    private val NOTE_TAGS = listOf("span", "a")

    private val SCRIPT_OR_STYLE = Regex(
        """<(script|style)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val HTML_TAG = Regex("""<[^>]+>""")
    private val NUMERIC_ENTITY = Regex("""&#(\d+);?""")
    private val WHITESPACE = Regex("""\s+""")
    private val ORPHANED_PUNCTUATION = Regex("""\s+([,;:])(?=\s|$)""")
}
