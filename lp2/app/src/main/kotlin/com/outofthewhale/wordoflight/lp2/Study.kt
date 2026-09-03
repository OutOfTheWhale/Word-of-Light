package com.outofthewhale.wordoflight.lp2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.outofthewhale.wordoflight.ChapterRef
import com.outofthewhale.wordoflight.ChapterRepository
import com.outofthewhale.wordoflight.Concordance
import com.outofthewhale.wordoflight.Fetched
import com.outofthewhale.wordoflight.Lexicon
import com.outofthewhale.wordoflight.Tagging
import com.outofthewhale.wordoflight.Testament
import com.outofthewhale.wordoflight.Translation
import com.outofthewhale.wordoflight.Translations
import com.outofthewhale.wordoflight.Verse
import com.outofthewhale.wordoflight.VerseRef
import com.outofthewhale.wordoflight.Word

/**
 * A verse laid out for display, with each word's span remembered.
 *
 * Identical in intent to the LP3 build: one text block so the verse still
 * wraps as prose, with the tapped word found by hit-testing the character
 * offset rather than by laying every word out as its own element.
 */
internal data class Rendered(
    val text: AnnotatedString,
    val spans: List<IntRange>,
    val words: List<Word>,
) {
    fun wordAt(offset: Int): Word? {
        val index = spans.indexOfFirst { offset in it }
        return if (index >= 0) words[index] else null
    }
}

internal fun renderVerse(verse: Verse): Rendered {
    val builder = AnnotatedString.Builder()
    val spans = mutableListOf<IntRange>()
    val words = mutableListOf<Word>()

    verse.lines.forEachIndexed { lineIndex, line ->
        if (lineIndex > 0) builder.append(if (line.poetry) "\n" else " ")

        val lineWords = line.words()
        if (lineWords.isEmpty()) {
            builder.append(line.display)
            return@forEachIndexed
        }

        lineWords.forEachIndexed { wordIndex, word ->
            if (wordIndex > 0) builder.append(' ')
            val start = builder.length
            if (word.added) {
                // Words the translators supplied, italic since 1611.
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                builder.append(word.text)
                builder.pop()
            } else {
                builder.append(word.text)
            }
            spans.add(start until builder.length)
            words.add(word)
        }
    }
    return Rendered(builder.toAnnotatedString(), spans, words)
}

// --- word study ---------------------------------------------------------

/**
 * What a word is in the original language, and everywhere else it is used.
 *
 * Only the bundled KJV carries Strong's tagging - it is the one translation
 * that is public domain, so the one that can be shipped tagged. A fetched
 * translation has no tags, and long-press finds nothing.
 */
@Composable
internal fun WordStudyView(
    word: Word,
    testament: Testament,
    lexicon: Lexicon,
    concordance: Concordance,
    onPick: (ChapterRef) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    val strong = word.strong

    Page(title = word.bare, onBack = onBack) {
        val language = if (testament == Testament.OLD) "Hebrew" else "Greek"

        if (strong == null) {
            BasicText(
                text = "This word carries no Strong's number.",
                style = type.paragraph.copy(color = palette.contentSecondary),
            )
            return@Page
        }

        BasicText(
            text = "$strong  ·  $language",
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.padding(bottom = 14.dp),
        )

        // A number from the wrong testament means the data is wrong, and a
        // confident wrong answer is worse than none.
        if (!Tagging.isConsistent(strong, testament)) {
            BasicText(
                text = "This word is tagged $strong, which does not belong to the " +
                    "$language of this testament. Not showing a definition rather " +
                    "than showing a wrong one.",
                style = type.paragraph.copy(color = palette.contentSecondary),
            )
            return@Page
        }

        val entry = lexicon.lookup(strong)
        if (entry != null && !entry.isEmpty) {
            if (entry.word.isNotBlank()) {
                BasicText(
                    text = entry.word,
                    style = type.heading.copy(color = palette.content),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (entry.translit.isNotBlank()) {
                BasicText(
                    text = entry.translit,
                    style = type.paragraph.copy(color = palette.contentSecondary),
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            // Usage leads: this lexicon's definition field splices the
            // transliteration in front of a fragment, while usage carries the
            // actual senses.
            Field("MEANING", entry.usage)
            Field("STRONG'S", entry.definition)
            Field("ROOT", entry.root)
            Field("PART OF SPEECH", entry.pos)
        } else {
            BasicText(
                text = "No dictionary entry for this number. The occurrences below " +
                    "still apply.",
                style = type.paragraph.copy(color = palette.contentSecondary),
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        val occurrences = concordance.occurrences(strong)
        if (occurrences.isNotEmpty()) {
            val shown = occurrences.take(OCCURRENCE_LIMIT)
            BasicText(
                text = "FOUND ${occurrences.size} VERSES",
                style = type.paragraph.copy(color = palette.content),
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            if (occurrences.size > shown.size) {
                BasicText(
                    text = "showing the first ${shown.size}",
                    style = type.fine.copy(color = palette.contentSecondary),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            shown.forEach { ref ->
                BasicText(
                    text = ref.label(),
                    style = type.paragraph.copy(color = palette.content),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(ref.chapterRef()) }
                        .padding(vertical = 7.dp),
                )
            }
        }
    }
}

// --- compare ------------------------------------------------------------

/** One passage, read in every translation available on this device. */
@Composable
internal fun CompareView(
    verses: List<VerseRef>,
    readable: Set<String>,
    repository: ChapterRepository,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    val wanted = remember(readable) { Translations.all.filter { it.id in readable } }
    var rows by remember { mutableStateOf(wanted.map { it to "…" }) }

    LaunchedEffect(verses, wanted) {
        // One at a time: each miss spends a request against a monthly
        // allowance, and the first row is readable while the rest arrive.
        wanted.forEachIndexed { index, translation ->
            val text = passage(repository, translation, verses)
            rows = rows.toMutableList().also { it[index] = translation to text }
        }
    }

    val title = when {
        verses.isEmpty() -> "Compare"
        verses.size == 1 -> verses.first().label()
        else -> "${verses.first().label()}-${verses.last().verse}"
    }

    Page(title = title, onBack = onBack) {
        if (wanted.size <= 1) {
            BasicText(
                text = "Only one translation is available. Add an API key in Settings " +
                    "to compare.",
                style = type.paragraph.copy(color = palette.contentSecondary),
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        rows.forEach { (translation, text) ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                BasicText(
                    text = translation.abbreviation,
                    style = type.fine.copy(color = palette.contentSecondary),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                BasicText(
                    text = text,
                    style = type.paragraph.copy(color = palette.content),
                )
            }
        }
    }
}

private suspend fun passage(
    repository: ChapterRepository,
    translation: Translation,
    verses: List<VerseRef>,
): String {
    val ref = verses.firstOrNull()?.chapterRef() ?: return "Nothing selected"
    return when (val result = repository.chapter(translation, ref)) {
        is Fetched.Ok -> {
            val wanted = verses.map { it.verse }.toSet()
            result.verses.filter { it.verse in wanted }
                .joinToString(" ") { it.render() }
                .ifBlank { "Not in this translation" }
        }
        Fetched.NoKey -> "No key saved"
        Fetched.NotEntitled -> "This key cannot read it"
        Fetched.QuotaExceeded -> "Request limit reached"
        is Fetched.Failed -> result.reason
    }
}

@Composable
private fun Field(label: String, value: String) {
    if (value.isBlank()) return
    val palette = LocalPalette.current
    val type = LocalTypography.current
    BasicText(
        text = label,
        style = type.fine.copy(color = palette.contentSecondary),
        modifier = Modifier.padding(bottom = 1.dp),
    )
    BasicText(
        text = value,
        style = type.paragraph.copy(color = palette.content),
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

/**
 * Common words run to thousands of verses - G2532 ("and") appears in 5,199.
 * The count is honest; the list is capped.
 */
private const val OCCURRENCE_LIMIT = 50
