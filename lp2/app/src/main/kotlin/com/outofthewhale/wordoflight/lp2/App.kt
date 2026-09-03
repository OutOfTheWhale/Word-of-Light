package com.outofthewhale.wordoflight.lp2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.outofthewhale.wordoflight.ApiKeyStore
import com.outofthewhale.wordoflight.Book
import com.outofthewhale.wordoflight.Canon
import com.outofthewhale.wordoflight.ChapterRef
import com.outofthewhale.wordoflight.ChapterRepository
import com.outofthewhale.wordoflight.Fetched
import com.outofthewhale.wordoflight.Marks
import com.outofthewhale.wordoflight.MarksStore
import com.outofthewhale.wordoflight.Translation
import com.outofthewhale.wordoflight.Translations
import com.outofthewhale.wordoflight.Verse
import kotlinx.coroutines.launch

private sealed interface Route {
    data object Reader : Route
    data object Books : Route
    data class Chapters(val book: Book) : Route
}

@Composable
fun WordOfLightApp(
    repository: ChapterRepository,
    marksStore: MarksStore,
    keyStore: ApiKeyStore,
) {
    var route by remember { mutableStateOf<Route>(Route.Reader) }
    var ref by remember { mutableStateOf(ChapterRef("gen", 1)) }
    var translation by remember { mutableStateOf(Translations.KJV) }
    var verses by remember { mutableStateOf<List<Verse>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }

    val marks by marksStore.marks.collectAsState(initial = Marks())
    val scope = rememberCoroutineScope()

    LaunchedEffect(ref, translation) {
        selection = emptySet()
        val local = repository.local(translation, ref)
        if (local != null) {
            verses = local
            status = null
        } else {
            verses = emptyList()
            status = "Fetching ${ref.label()} in ${translation.abbreviation}…"
            when (val result = repository.chapter(translation, ref)) {
                is Fetched.Ok -> {
                    verses = result.verses
                    status = null
                }
                Fetched.NoKey -> status = "No key saved for ${translation.abbreviation}."
                Fetched.NotEntitled -> status = "This key cannot read ${translation.abbreviation}."
                Fetched.QuotaExceeded -> status = "Request limit reached."
                is Fetched.Failed -> status = result.reason
            }
        }
    }

    val palette = LocalPalette.current

    Box(modifier = Modifier.fillMaxSize().background(palette.background)) {
        when (val current = route) {
            Route.Reader -> Reader(
                ref = ref,
                translation = translation,
                verses = verses,
                status = status,
                marks = marks,
                selection = selection,
                onOpenBooks = { route = Route.Books },
                onToggleSelect = { key ->
                    selection = if (key in selection) selection - key else selection + key
                },
                onUnderline = {
                    val chosen = selection
                    if (chosen.isNotEmpty()) {
                        scope.launch {
                            marksStore.update {
                                it.toggleHighlight(chosen, System.currentTimeMillis())
                            }
                        }
                        selection = emptySet()
                    }
                },
                onClearSelection = { selection = emptySet() },
                onGoTo = { ref = it },
            )

            Route.Books -> BookList(
                onPick = { book ->
                    route = if (book.chapters == 1) {
                        ref = ChapterRef(book.id, 1)
                        Route.Reader
                    } else {
                        Route.Chapters(book)
                    }
                },
                onBack = { route = Route.Reader },
            )

            is Route.Chapters -> ChapterGrid(
                book = current.book,
                onPick = { chapter ->
                    ref = ChapterRef(current.book.id, chapter)
                    route = Route.Reader
                },
                onBack = { route = Route.Books },
            )
        }
    }
}

// --- reader -------------------------------------------------------------

@Composable
private fun Reader(
    ref: ChapterRef,
    translation: Translation,
    verses: List<Verse>,
    status: String?,
    marks: Marks,
    selection: Set<String>,
    onOpenBooks: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onUnderline: () -> Unit,
    onClearSelection: () -> Unit,
    onGoTo: (ChapterRef) -> Unit,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    val scroll = rememberScrollState()

    LaunchedEffect(ref) { scroll.scrollTo(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
        ) {
            BasicText(
                text = ref.label(),
                style = type.subheading.copy(color = palette.content),
                modifier = Modifier.weight(1f).clickable(onClick = onOpenBooks),
            )
            BasicText(
                text = translation.abbreviation,
                style = type.fine.copy(color = palette.contentSecondary),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp)
        ) {
            if (selection.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    FooterText("UNDERLINE", onClick = onUnderline)
                    FooterText("CLEAR", onClick = onClearSelection)
                }
            }

            if (verses.isEmpty()) {
                BasicText(
                    text = status ?: "Nothing here.",
                    style = type.paragraph.copy(color = palette.contentSecondary),
                )
            } else {
                verses.forEach { verse ->
                    val key = "${ref.book}.${verse.chapter}.${verse.verse}"
                    VerseRow(
                        verse = verse,
                        highlighted = marks.isHighlighted(key),
                        selected = key in selection,
                        onTap = { onToggleSelect(key) },
                    )
                }
            }

            Box(modifier = Modifier.height(20.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp)
        ) {
            val previous = Canon.previous(ref)
            val next = Canon.next(ref)
            if (previous != null) {
                FooterText("‹ ${step(previous, ref)}") { onGoTo(previous) }
            }
            Column(modifier = Modifier.weight(1f)) {}
            if (next != null) {
                FooterText("${step(next, ref)} ›") { onGoTo(next) }
            }
        }
    }
}

@Composable
private fun VerseRow(
    verse: Verse,
    highlighted: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    verse.heading?.let { heading ->
        BasicText(
            text = heading,
            style = type.subheading.copy(color = palette.content),
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(bottom = 10.dp)
    ) {
        // Same rule as the LP3 build: an underline is a highlight, a margin bar
        // is a pending selection. Two greys would be two ways of looking alike.
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .then(
                    if (selected) Modifier.background(palette.contentSecondary) else Modifier
                )
        )
        BasicText(
            text = verse.verse.toString(),
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.width(30.dp).padding(start = 6.dp),
        )
        BasicText(
            text = verse.render(),
            style = type.paragraph.copy(
                color = palette.content,
                textDecoration = if (highlighted) TextDecoration.Underline else null,
            ),
            modifier = Modifier.weight(1f).clickable(onClick = onTap),
        )
    }
}

// --- pickers ------------------------------------------------------------

@Composable
private fun BookList(onPick: (Book) -> Unit, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        BasicText(
            text = "Books",
            style = type.heading.copy(color = palette.content),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        listOf(true, false).forEach { old ->
            BasicText(
                text = if (old) "OLD TESTAMENT" else "NEW TESTAMENT",
                style = type.fine.copy(color = palette.contentSecondary),
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
            Canon.books.filter { it.isOldTestament == old }.forEach { book ->
                BasicText(
                    text = book.name,
                    style = type.paragraph.copy(color = palette.content),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(book) }
                        .padding(vertical = 8.dp),
                )
            }
        }
        FooterText("BACK", onClick = onBack)
    }
}

@Composable
private fun ChapterGrid(book: Book, onPick: (Int) -> Unit, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        BasicText(
            text = book.name,
            style = type.heading.copy(color = palette.content),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        (1..book.chapters).chunked(COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { chapter ->
                    BasicText(
                        text = chapter.toString(),
                        style = type.paragraph.copy(color = palette.content),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPick(chapter) }
                            .padding(vertical = 10.dp),
                    )
                }
                repeat(COLUMNS - row.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
        FooterText("BACK", onClick = onBack)
    }
}

// --- shared bits --------------------------------------------------------

@Composable
private fun FooterText(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    BasicText(
        text = label,
        style = type.fine.copy(color = palette.content, textAlign = TextAlign.Center),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/**
 * Within a book the chapter number alone is enough - the book is named in the
 * header. Crossing into another book is the case that needs spelling out.
 */
private fun step(target: ChapterRef, current: ChapterRef): String =
    if (target.book == current.book) target.chapter.toString() else target.label()

private const val COLUMNS = 5
