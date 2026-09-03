package com.outofthewhale.wordoflight.lp2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.outofthewhale.wordoflight.ApiKeyStore
import com.outofthewhale.wordoflight.Book
import com.outofthewhale.wordoflight.Canon
import com.outofthewhale.wordoflight.ChapterRef
import com.outofthewhale.wordoflight.ChapterRepository
import com.outofthewhale.wordoflight.Concordance
import com.outofthewhale.wordoflight.Lexicon
import com.outofthewhale.wordoflight.Fetched
import com.outofthewhale.wordoflight.Marks
import com.outofthewhale.wordoflight.MarksStore
import com.outofthewhale.wordoflight.Source
import com.outofthewhale.wordoflight.Translation
import com.outofthewhale.wordoflight.Translations
import com.outofthewhale.wordoflight.Testament
import com.outofthewhale.wordoflight.Verse
import com.outofthewhale.wordoflight.VerseRef
import com.outofthewhale.wordoflight.Word
import kotlinx.coroutines.launch

internal sealed interface Route {
    data object Reader : Route
    data object Menu : Route
    data object Books : Route
    data object Settings : Route
    data class Chapters(val book: Book) : Route
    data class Marks(val list: MarkList) : Route
    data class Note(val refs: Set<String>, val title: String, val initial: String) : Route
    data class Study(val word: Word, val testament: Testament) : Route
    data class Compare(val verses: List<VerseRef>) : Route
}

@Composable
fun WordOfLightApp(
    repository: ChapterRepository,
    marksStore: MarksStore,
    keyStore: ApiKeyStore,
    lexicon: Lexicon,
    concordance: Concordance,
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
                // Underlining and marking leave the selection in place, so a
                // passage can be underlined and then annotated without picking
                // the same verses out twice. NOTE and CLEAR end it.
                onUnderline = {
                    val chosen = selection
                    if (chosen.isNotEmpty()) {
                        scope.launch {
                            marksStore.update {
                                it.toggleHighlight(chosen, System.currentTimeMillis())
                            }
                        }
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
                    }
                },
                onNote = {
                    val chosen = selection.mapNotNull(VerseRef::parse).sortedBy { it.verse }
                    if (chosen.isNotEmpty()) {
                        // Blank unless every verse already carries the same
                        // note, so opening the editor cannot silently pick one
                        // and overwrite the rest with it.
                        val first = marks.noteFor(selection.min())
                        val shared = if (selection.all { marks.noteFor(it) == first }) first else ""
                        route = Route.Note(
                            refs = selection,
                            title = if (chosen.size == 1) {
                                chosen.first().label()
                            } else {
                                "${chosen.first().label()}-${chosen.last().verse}"
                            },
                            initial = shared,
                        )
                    }
                },
                onCompare = {
                    val chosen = selection.mapNotNull(VerseRef::parse).sortedBy { it.verse }
                    if (chosen.isNotEmpty()) route = Route.Compare(chosen)
                },
                onStudyWord = { word ->
                    if (word.strong != null) {
                        val testament = Canon.book(ref.book)?.testament ?: Testament.OLD
                        route = Route.Study(word, testament)
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

            is Route.Study -> WordStudyView(
                word = current.word,
                testament = current.testament,
                lexicon = lexicon,
                concordance = concordance,
                onPick = {
                    ref = it
                    route = Route.Reader
                },
                onBack = { route = Route.Reader },
            )

            is Route.Compare -> CompareView(
                verses = current.verses,
                readable = readable,
                repository = repository,
                onBack = { route = Route.Reader },
            )

            is Route.Note -> NoteEditor(
                title = current.title,
                initial = current.initial,
                onSave = { text ->
                    scope.launch {
                        marksStore.update {
                            it.withNoteOn(current.refs, text, System.currentTimeMillis())
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
    onCompare: () -> Unit,
    onStudyWord: (Word) -> Unit,
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
            BookmarkFlag(
                flagged = marks.isChapterBookmarked(ref),
                onClick = onToggleFlag,
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
                Row(modifier = Modifier.fillMaxWidth()) {
                    Action("UNDERLINE", onUnderline)
                    Action("COMPARE", onCompare)
                    Action("NOTE", onNote)
                }
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
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
                        onLongPressWord = onStudyWord,
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
    onLongPressWord: (Word) -> Unit,
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
        val rendered = remember(verse) { renderVerse(verse) }
        val style = type.paragraph.copy(
            color = palette.content,
            textDecoration = if (highlighted) TextDecoration.Underline else null,
        )

        if (rendered.words.isEmpty()) {
            // A fetched translation carries no Strong's tagging, so there is
            // nothing to long-press into. Only the bundled KJV is tagged.
            BasicText(
                text = verse.render(),
                style = style,
                modifier = Modifier.weight(1f).clickable(onClick = onTap),
            )
        } else {
            var layout by remember(verse) { mutableStateOf<TextLayoutResult?>(null) }
            // One text block, so the verse still wraps as prose; the tapped
            // word is found by hit-testing the character offset.
            BasicText(
                text = rendered.text,
                style = style,
                onTextLayout = { layout = it },
                modifier = Modifier.weight(1f).pointerInput(rendered) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { position ->
                            val result = layout ?: return@detectTapGestures
                            rendered.wordAt(result.getOffsetForPosition(position))
                                ?.let(onLongPressWord)
                        },
                    )
                },
            )
        }
    }
}

/**
 * The chapter flag: a ribbon, filled when set and outlined when not.
 *
 * Drawn rather than lettered so the states differ in fill rather than in
 * shade - on a black-and-white screen a dim glyph beside a bright one reads as
 * low contrast, not as "off". It takes the theme's content colour, so it
 * inverts with light mode without a second asset.
 *
 * The shape is small; the touch target around it is not.
 */
@Composable
private fun BookmarkFlag(flagged: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Canvas(modifier = Modifier.size(width = 15.dp, height = 21.dp)) {
            val notch = size.height * NOTCH_FRACTION
            val ribbon = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2f, size.height - notch)
                lineTo(0f, size.height)
                close()
            }
            if (flagged) {
                drawPath(ribbon, palette.content)
            } else {
                drawPath(ribbon, palette.contentSecondary, style = Stroke(width = OUTLINE_WIDTH))
            }
        }
    }
}

/** Within a book the number is enough; the header names the book. */
internal fun step(target: ChapterRef, current: ChapterRef): String =
    if (target.book == current.book) target.chapter.toString() else target.label()

/** How far up the ribbon the notch is cut, as a share of its height. */
private const val NOTCH_FRACTION = 0.3f
private const val OUTLINE_WIDTH = 3f
