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
import com.outofthewhale.wordoflight.Source
import com.outofthewhale.wordoflight.Translation
import com.outofthewhale.wordoflight.Translations
import com.outofthewhale.wordoflight.Verse
import kotlinx.coroutines.launch

internal sealed interface Route {
    data object Reader : Route
    data object Menu : Route
    data object Books : Route
    data object Settings : Route
    data class Chapters(val book: Book) : Route
    data class Marks(val list: MarkList) : Route
    data class Note(val anchor: String, val title: String, val initial: String) : Route
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
    var restored by remember { mutableStateOf(false) }

    val marks by marksStore.marks.collectAsState(initial = Marks())
    val readable by keyStore.readableTranslations.collectAsState(initial = setOf("kjv"))
    val scope = rememberCoroutineScope()

    // Pick up where reading left off, translation included. Once only: later
    // mark changes must not drag the reader somewhere else.
    LaunchedEffect(marks) {
        if (!restored && marks.recents.isNotEmpty()) {
            restored = true
            marks.lastRead?.let { last ->
                last.chapterRef()?.let { saved ->
                    ref = saved
                    Translations.byId(last.translation)?.let { translation = it }
                }
            }
        } else if (!restored) {
            restored = true
        }
    }

    LaunchedEffect(ref, translation, restored) {
        if (!restored) return@LaunchedEffect
        selection = emptySet()

        // Every chapter displayed is the history and the resume point both.
        val front = marks.lastRead
        if (front?.ref != ref.key() || front.translation != translation.id) {
            scope.launch {
                marksStore.update { it.withVisit(ref, translation.id, System.currentTimeMillis()) }
            }
        }

        val local = repository.local(translation, ref)
        if (local != null) {
            verses = local
            status = null
        } else {
            verses = emptyList()
            if (translation.source != Source.API) {
                status = "${ref.label()} is not on this device in ${translation.abbreviation}"
            } else {
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
    }

    Box(modifier = Modifier.fillMaxSize().background(LocalPalette.current.background)) {
        when (val current = route) {
            Route.Reader -> Reader(
                ref = ref,
                translation = translation,
                verses = verses,
                status = status,
                marks = marks,
                selection = selection,
                readable = readable,
                onOpenMenu = { route = Route.Menu },
                onOpenBooks = { route = Route.Books },
                onToggleFlag = {
                    scope.launch { marksStore.update { it.toggleChapterBookmark(ref) } }
                },
                onCycleTranslation = {
                    val options = Translations.all.filter { it.id in readable }
                    if (options.size > 1) {
                        val next = options[(options.indexOf(translation) + 1) % options.size]
                        translation = next
                    }
                },
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
                onMark = {
                    val chosen = selection
                    if (chosen.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        scope.launch {
                            marksStore.update { existing ->
                                chosen.fold(existing) { acc, k -> acc.toggleBookmark(k, now) }
                            }
                        }
                        selection = emptySet()
                    }
                },
                onNote = {
                    val anchor = selection.minOrNull()
                    if (anchor != null) {
                        route = Route.Note(
                            anchor = anchor,
                            title = com.outofthewhale.wordoflight.VerseRef.parse(anchor)
                                ?.label() ?: "Note",
                            initial = marks.noteFor(anchor),
                        )
                    }
                },
                onClearSelection = { selection = emptySet() },
                onGoTo = { ref = it },
            )

            Route.Menu -> MenuList(
                marks = marks,
                onBooks = { route = Route.Books },
                onList = { route = Route.Marks(it) },
                onSettings = { route = Route.Settings },
                onBack = { route = Route.Reader },
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

            is Route.Marks -> MarkListView(
                list = current.list,
                marks = marks,
                onPick = {
                    ref = it
                    route = Route.Reader
                },
                onBack = { route = Route.Menu },
            )

            Route.Settings -> SettingsView(
                keyStore = keyStore,
                onBack = { route = Route.Menu },
            )

            is Route.Note -> NoteEditor(
                title = current.title,
                initial = current.initial,
                onSave = { text ->
                    scope.launch {
                        marksStore.update {
                            it.withNote(current.anchor, text, System.currentTimeMillis())
                        }
                    }
                    selection = emptySet()
                    route = Route.Reader
                },
                onBack = { route = Route.Reader },
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
    readable: Set<String>,
    onOpenMenu: () -> Unit,
    onOpenBooks: () -> Unit,
    onToggleFlag: () -> Unit,
    onCycleTranslation: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onUnderline: () -> Unit,
    onMark: () -> Unit,
    onNote: () -> Unit,
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
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
        ) {
            // No SDK icon set here, so the menu is drawn as a glyph.
            BasicText(
                text = "≡",
                style = type.subheading.copy(color = palette.content),
                modifier = Modifier.clickable(onClick = onOpenMenu).padding(end = 12.dp),
            )
            BasicText(
                text = ref.label(),
                style = type.subheading.copy(color = palette.content),
                modifier = Modifier.weight(1f).clickable(onClick = onOpenBooks),
            )
            // Bright means flagged. Same "*" a bookmarked verse carries, one
            // mark at two scales.
            BasicText(
                text = "*",
                style = type.subheading.copy(
                    color = if (marks.isChapterBookmarked(ref)) {
                        palette.content
                    } else {
                        palette.contentSecondary
                    }
                ),
                modifier = Modifier.clickable(onClick = onToggleFlag).padding(horizontal = 10.dp),
            )
            BasicText(
                text = translation.abbreviation,
                style = type.fine.copy(color = palette.contentSecondary),
                modifier = Modifier.clickable(onClick = onCycleTranslation),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp)
        ) {
            if (selection.isNotEmpty()) {
                BasicText(
                    text = if (selection.size == 1) "1 VERSE" else "${selection.size} VERSES",
                    style = type.fine.copy(color = palette.contentSecondary),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Action("UNDERLINE", onUnderline)
                    Action("NOTE", onNote)
                    Action("MARK", onMark)
                    Action("CLEAR", onClearSelection)
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
                        bookmarked = marks.forVerse(key)?.bookmarked == true,
                        hasNote = marks.forVerse(key)?.hasNote == true,
                        onTap = { onToggleSelect(key) },
                    )
                }
            }

            // Chapter navigation lives at the end of the text, where finishing
            // a chapter leaves you.
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp)) {
                val previous = Canon.previous(ref)
                val next = Canon.next(ref)
                if (previous != null) {
                    Action("‹ ${step(previous, ref)}") { onGoTo(previous) }
                }
                Column(modifier = Modifier.weight(1f)) {}
                if (next != null) {
                    Action("${step(next, ref)} ›") { onGoTo(next) }
                }
            }
        }
    }
}

@Composable
private fun VerseRow(
    verse: Verse,
    highlighted: Boolean,
    selected: Boolean,
    bookmarked: Boolean,
    hasNote: Boolean,
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
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .then(
                    if (selected) Modifier.background(palette.contentSecondary) else Modifier
                )
        )
        BasicText(
            text = buildString {
                append(verse.verse)
                if (bookmarked) append("*")
                if (hasNote) append("·")
            },
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.width(34.dp).padding(start = 6.dp),
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

/** Within a book the number is enough; the header names the book. */
internal fun step(target: ChapterRef, current: ChapterRef): String =
    if (target.book == current.book) target.chapter.toString() else target.label()
