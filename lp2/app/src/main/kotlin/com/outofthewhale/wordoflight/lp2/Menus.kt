package com.outofthewhale.wordoflight.lp2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.outofthewhale.wordoflight.ApiKeyStore
import com.outofthewhale.wordoflight.ApiProvider
import com.outofthewhale.wordoflight.BibleApi
import com.outofthewhale.wordoflight.Book
import com.outofthewhale.wordoflight.Canon
import com.outofthewhale.wordoflight.ChapterRef
import com.outofthewhale.wordoflight.Marks
import com.outofthewhale.wordoflight.Translations
import kotlinx.coroutines.launch

/** Which saved things a list is showing. Mirrors the LP3 build. */
enum class MarkList(val title: String, val empty: String) {
    RECENTS("Recents", "Chapters you open are listed here, most recent first."),
    BOOKMARKS("Bookmarks", "Flag a chapter from the header, or select a verse and choose MARK."),
    NOTES("Notes", "Select a verse while reading and choose NOTE."),
    HIGHLIGHTS("Highlights", "Select a verse while reading and choose UNDERLINE."),
}

@Composable
internal fun MenuList(
    marks: Marks,
    onBooks: () -> Unit,
    onList: (MarkList) -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    Page(title = "Menu", onBack = onBack) {
        Item("Books", "Choose a book and chapter", onBooks)
        Item("Recents", count(marks.recents.size, "chapter")) { onList(MarkList.RECENTS) }
        Item(
            "Bookmarks",
            count(marks.chapterBookmarks.size + marks.bookmarks.size, "saved"),
        ) { onList(MarkList.BOOKMARKS) }
        // noteEntries, not notes: the count has to agree with the list, and a
        // note written across a passage is one note there.
        Item("Notes", count(marks.chapterNotes.size + marks.noteEntries.size, "note")) {
            onList(MarkList.NOTES)
        }
        Item("Highlights", count(marks.highlights.size, "verse")) {
            onList(MarkList.HIGHLIGHTS)
        }
        Item("Settings", "Translations, keys and theme", onSettings)
    }
}

@Composable
internal fun MarkListView(
    list: MarkList,
    marks: Marks,
    onPick: (ChapterRef) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    val entries = entriesFor(list, marks)

    Page(title = list.title, onBack = onBack) {
        if (entries.isEmpty()) {
            BasicText(
                text = list.empty,
                style = type.paragraph.copy(color = palette.contentSecondary),
            )
        }
        entries.forEach { (ref, label, detail) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(ref) }
                    .padding(vertical = 8.dp)
            ) {
                BasicText(text = label, style = type.paragraph.copy(color = palette.content))
                if (!detail.isNullOrBlank()) {
                    BasicText(
                        text = detail,
                        style = type.fine.copy(color = palette.contentSecondary),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private data class Entry(val ref: ChapterRef, val label: String, val detail: String?)

private fun entriesFor(list: MarkList, marks: Marks): List<Entry> = when (list) {
    MarkList.RECENTS -> marks.recents.mapNotNull { recent ->
        recent.chapterRef()?.let {
            Entry(it, it.label(), Translations.byId(recent.translation)?.abbreviation)
        }
    }

    // Chapters and verses together: the same gesture at two scales, and
    // splitting them would mean guessing which list a thing landed in.
    MarkList.BOOKMARKS ->
        marks.bookmarkedChapters.map { Entry(it, it.label(), "chapter") } +
            marks.bookmarks.mapNotNull { mark ->
                mark.verseRef()?.let { Entry(it.chapterRef(), it.label(), null) }
            }

    // noteEntries, not notes: one thought written across a passage is one line
    // here, not the same sentence once per verse.
    MarkList.NOTES ->
        marks.annotatedChapters.map { Entry(it, it.label(), marks.chapterNote(it)) } +
            marks.noteEntries.map { Entry(it.chapterRef(), it.label, it.note) }

    MarkList.HIGHLIGHTS -> marks.highlights.mapNotNull { mark ->
        mark.verseRef()?.let { Entry(it.chapterRef(), it.label(), null) }
    }
}

// --- pickers ------------------------------------------------------------

@Composable
internal fun BookList(onPick: (Book) -> Unit, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    Page(title = "Books", onBack = onBack) {
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
    }
}

@Composable
internal fun ChapterGrid(book: Book, onPick: (Int) -> Unit, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    Page(title = book.name, onBack = onBack) {
        (1..book.chapters).chunked(COLUMNS).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { chapter ->
                    BasicText(
                        text = chapter.toString(),
                        style = type.paragraph.copy(
                            color = palette.content,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPick(chapter) }
                            .padding(vertical = 10.dp),
                    )
                }
                repeat(COLUMNS - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

// --- settings -----------------------------------------------------------

@Composable
internal fun SettingsView(keyStore: ApiKeyStore, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    val configured by keyStore.configured.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<ApiProvider?>(null) }
    var entry by remember { mutableStateOf("") }
    var discovery by remember { mutableStateOf<String?>(null) }

    Page(title = "Settings", onBack = onBack) {
        BasicText(
            text = "The KJV is built in and needs no key. Other translations are " +
                "fetched from their publishers.",
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.padding(bottom = 12.dp),
        )

        ApiProvider.entries.forEach { provider ->
            val unlocks = Translations.all
                .filter { it.provider == provider }
                .joinToString(", ") { it.abbreviation }

            Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                BasicText(
                    text = provider.displayName,
                    style = type.paragraph.copy(color = palette.content),
                )
                BasicText(
                    text = "$unlocks  ·  ${provider.keyUrl}",
                    style = type.fine.copy(color = palette.contentSecondary),
                )

                if (editing == provider) {
                    // A stored key is never read back, so editing starts blank.
                    BasicTextField(
                        value = entry,
                        onValueChange = { entry = it },
                        textStyle = type.paragraph.copy(color = palette.content),
                        cursorBrush = SolidColor(palette.content),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 4.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(palette.contentSecondary)
                    )
                    Row(modifier = Modifier.padding(top = 6.dp)) {
                        Action("SAVE") {
                            val value = entry
                            scope.launch {
                                keyStore.setKey(provider, value)
                                if (provider == ApiProvider.API_BIBLE && value.isNotBlank()) {
                                    discovery = "Checking what this key can read…"
                                    discovery = discover(keyStore)
                                }
                            }
                            entry = ""
                            editing = null
                        }
                        Action("CANCEL") {
                            entry = ""
                            editing = null
                        }
                    }
                } else {
                    Row {
                        Action(
                            if (provider in configured) "KEY SAVED" else "NO KEY — TAP TO ADD"
                        ) {
                            entry = ""
                            editing = provider
                        }
                        if (provider in configured) {
                            Action("REMOVE") {
                                scope.launch { keyStore.clear(provider) }
                            }
                        }
                    }
                }
            }
        }

        discovery?.let {
            BasicText(
                text = it,
                style = type.fine.copy(color = palette.contentSecondary),
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        BasicText(
            text = "THEME",
            style = type.fine.copy(color = palette.contentSecondary),
            modifier = Modifier.padding(top = 22.dp, bottom = 2.dp),
        )
        BasicText(
            text = if (ThemeController.isDark) "Dark" else "Light",
            style = type.paragraph.copy(color = palette.content),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { ThemeController.toggle() }
                .padding(vertical = 6.dp),
        )
    }
}

/**
 * Asks API.Bible what this key can read and binds the ids.
 *
 * Doubles as validation at the moment the key is entered, rather than a
 * chapter silently failing later. The free tier grants three translations
 * chosen per account, so the ids cannot be hardcoded.
 */
private suspend fun discover(keyStore: ApiKeyStore): String {
    val api = BibleApi(keyStore)
    return try {
        api.bibles().fold(
            onSuccess = { remote ->
                val wanted = Translations.all.filter { it.provider == ApiProvider.API_BIBLE }
                val bindings = wanted.mapNotNull { translation ->
                    remote.firstOrNull {
                        it.shortName.equals(translation.abbreviation, ignoreCase = true)
                    }?.let { translation.id to it.id }
                }.toMap()
                keyStore.bindBibleIds(bindings)
                if (bindings.isEmpty()) {
                    "Key accepted, but it grants none of " +
                        wanted.joinToString(", ") { it.abbreviation }
                } else {
                    "Ready: " + bindings.keys
                        .mapNotNull { Translations.byId(it)?.abbreviation }
                        .joinToString(", ")
                }
            },
            onFailure = { it.message ?: "Could not reach API.Bible" },
        )
    } finally {
        api.close()
    }
}

// --- note editor --------------------------------------------------------

@Composable
internal fun NoteEditor(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    var text by remember { mutableStateOf(initial) }

    Page(title = title, onBack = onBack) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = type.paragraph.copy(color = palette.content),
            cursorBrush = SolidColor(palette.content),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.contentSecondary)
        )
        // Saving an empty note is how a note is deleted: Marks drops a record
        // once nothing is left on it.
        Action("SAVE") { onSave(text) }
    }
}

// --- shared bits --------------------------------------------------------

/** Every non-reading screen: a title, a scrolling body, and a way back. */
@Composable
internal fun Page(title: String, onBack: () -> Unit, body: @Composable () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        BasicText(
            text = title,
            style = type.heading.copy(color = palette.content),
            modifier = Modifier.padding(bottom = 10.dp),
        )
        body()
        Box(modifier = Modifier.height(20.dp))
        Action("BACK", onBack)
    }
}

@Composable
private fun Item(label: String, detail: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        BasicText(text = label, style = type.subheading.copy(color = palette.content))
        BasicText(text = detail, style = type.fine.copy(color = palette.contentSecondary))
    }
}

@Composable
internal fun Action(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val type = LocalTypography.current
    BasicText(
        text = label,
        style = type.fine.copy(color = palette.content),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
    )
}

private fun count(size: Int, noun: String): String = when (size) {
    0 -> "Nothing yet"
    1 -> "1 $noun"
    else -> "$size ${noun}s"
}

private const val COLUMNS = 5
