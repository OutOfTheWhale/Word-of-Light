package com.outofthewhale.wordoflight

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ReaderViewModel(
    val repository: ChapterRepository,
    private val marksStore: MarksStore,
    private val keyStore: ApiKeyStore,
) : LightViewModel<Unit>() {

    // Opening position until the resume-where-you-left-off store lands.
    private val _ref = MutableStateFlow(ChapterRef("gen", 1))
    val ref: StateFlow<ChapterRef> = _ref

    private val _translation = MutableStateFlow(Translations.KJV)
    val translation: StateFlow<Translation> = _translation

    private val _verses = MutableStateFlow<List<Verse>>(emptyList())
    val verses: StateFlow<List<Verse>> = _verses

    private val _marks = MutableStateFlow(Marks())
    val marks: StateFlow<Marks> = _marks

    /** Verses tapped but not yet acted on, as "gen.1.1" keys. */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection

    /** A message to show instead of the text: fetching, or why there is none. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    /** Translations that can actually be read right now. */
    private val _readable = MutableStateFlow(setOf(Translations.KJV.id))
    val readable: StateFlow<Set<String>> = _readable

    /** Why each translation cannot be read, keyed by id; null means it can. */
    private val _blocked = MutableStateFlow<Map<String, String?>>(emptyMap())
    val blocked: StateFlow<Map<String, String?>> = _blocked

    init {
        viewModelScope.launch {
            marksStore.marks.collect { _marks.value = it }
        }
        viewModelScope.launch {
            // An API translation is readable once its provider has a key *and*
            // we know which remote Bible backs it.
            combine(keyStore.configured, keyStore.bibleIds) { providers, bindings ->
                Translations.all.associate { it.id to it.blockedReason(providers, bindings.keys) }
            }.collect { reasons ->
                _blocked.value = reasons
                _readable.value = reasons.filterValues { it == null }.keys
            }
        }
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reload()
    }

    private fun reload() {
        val translation = _translation.value
        val ref = _ref.value

        // Already on the device: bundled, sideloaded, or fetched once before.
        repository.local(translation, ref)?.let { verses ->
            _verses.value = verses
            _status.value = null
            return
        }

        _verses.value = emptyList()
        if (translation.source != Source.API) {
            _status.value = "${ref.label()} is not on this device in " +
                translation.abbreviation
            return
        }

        _status.value = "Fetching ${ref.label()} in ${translation.abbreviation}…"
        viewModelScope.launch { fetch(translation, ref) }
    }

    private suspend fun fetch(translation: Translation, ref: ChapterRef) {
        when (val result = repository.chapter(translation, ref)) {
            is Fetched.Ok -> {
                // The reader may have moved on underneath a slow request.
                if (_ref.value == ref && _translation.value == translation) {
                    _verses.value = result.verses
                    _status.value = null
                }
            }
            Fetched.NoKey -> _status.value =
                "No ${translation.provider?.displayName} key saved."
            Fetched.NotEntitled -> _status.value =
                "This key cannot read ${translation.abbreviation}."
            Fetched.QuotaExceeded -> _status.value =
                "Monthly request limit reached for ${translation.abbreviation}."
            is Fetched.Failed -> _status.value = result.reason
        }
    }

    fun goTo(ref: ChapterRef) {
        _ref.value = ref
        clearSelection()
        reload()
    }

    fun switchTo(translation: Translation) {
        _translation.value = translation
        reload()
    }

    fun testament(): Testament =
        Canon.book(_ref.value.book)?.testament ?: Testament.OLD

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }

    // --- selection ------------------------------------------------------

    fun keyOf(verse: Verse): String = "${_ref.value.book}.${verse.chapter}.${verse.verse}"

    /** Single tap adds to the selection rather than replacing it. */
    fun toggleSelection(key: String) {
        val current = _selection.value
        _selection.value = if (key in current) current - key else current + key
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    val selectionLabel: String
        get() = _selection.value.size.let { if (it == 1) "1 verse" else "$it verses" }

    /** The selection as references, in the order they appear in the chapter. */
    fun selectedVerses(): List<VerseRef> = _selection.value
        .mapNotNull(VerseRef::parse)
        .sortedBy { it.verse }

    // --- marks ----------------------------------------------------------

    fun highlightSelection() {
        val refs = _selection.value
        if (refs.isEmpty()) return
        edit { it.toggleHighlight(refs, System.currentTimeMillis()) }
        clearSelection()
    }

    fun bookmarkSelection() {
        val refs = _selection.value
        if (refs.isEmpty()) return
        val now = System.currentTimeMillis()
        edit { marks -> refs.fold(marks) { acc, ref -> acc.toggleBookmark(ref, now) } }
        clearSelection()
    }

    /** The note hangs on the first verse of the selection. */
    fun noteAnchor(): String? = _selection.value.minByOrNull { it }

    fun existingNote(): String = noteAnchor()?.let { _marks.value.noteFor(it) }.orEmpty()

    fun saveNote(text: String) {
        val anchor = noteAnchor() ?: return
        edit { it.withNote(anchor, text, System.currentTimeMillis()) }
        clearSelection()
    }

    private fun edit(transform: (Marks) -> Marks) {
        viewModelScope.launch { marksStore.update(transform) }
    }
}

@InitialScreen
class ReaderScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ReaderViewModel>(sealedActivity) {

    private val lexicon by lazy { Lexicon(lightContext::readAsset) }
    private val concordance by lazy { Concordance(lightContext::readAsset) }
    private val keyStore by lazy { ApiKeyStore(lightContext.dataStore) }

    override val viewModelClass: Class<ReaderViewModel>
        get() = ReaderViewModel::class.java

    override fun createViewModel() = ReaderViewModel(
        ChapterRepository(
            store = ModuleStore(lightContext.filesDir, lightContext::readAsset),
            cache = ChapterCache(lightContext.filesDir),
            keyStore = keyStore,
            bibleApi = BibleApi(keyStore),
            nltApi = NltApi(keyStore),
        ),
        MarksStore(lightContext.dataStore),
        keyStore,
    )

    @Composable
    override fun Content() {
        val ref by viewModel.ref.collectAsState()
        val translation by viewModel.translation.collectAsState()
        val verses by viewModel.verses.collectAsState()
        val marks by viewModel.marks.collectAsState()
        val selection by viewModel.selection.collectAsState()
        val status by viewModel.status.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        val scrollState = rememberScrollState()
        var footerVisible by remember { mutableStateOf(true) }

        // Turning the page should land at the top of the new chapter, not
        // halfway down it because that is where the last one was left.
        LaunchedEffect(ref) {
            scrollState.scrollTo(0)
            footerVisible = true
        }

        // The footer gets out of the way while reading forward and comes back
        // on the way up. It also stays put at either end, where there is no
        // reading direction to infer and hiding it would just look broken.
        LaunchedEffect(scrollState) {
            var anchor = scrollState.value
            snapshotFlow { scrollState.value }.collect { position ->
                when {
                    position <= 0 || position >= scrollState.maxValue -> {
                        footerVisible = true
                        anchor = position
                    }
                    position - anchor > SCROLL_THRESHOLD -> {
                        footerVisible = false
                        anchor = position
                    }
                    anchor - position > SCROLL_THRESHOLD -> {
                        footerVisible = true
                        anchor = position
                    }
                }
            }
        }

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
            ) {
                // Pinned. Changing book, chapter or version should never mean
                // scrolling somewhere first to find the control. Kept low so
                // the bar costs as little reading space as possible.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp)
                ) {
                    LightText(
                        text = ref.label(),
                        variant = LightTextVariant.Subheading,
                        modifier = Modifier
                            .weight(1f)
                            .lightClickable { openBooks() },
                    )
                    LightText(
                        text = translation.abbreviation,
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier.lightClickable { openVersions(translation) },
                    )
                }

                // LightScrollView, never a bare Column: on the 1080x1240 screen
                // a plain Column silently clips whatever sits at the bottom.
                LightScrollView(
                    scrollState = scrollState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 24.dp),
                ) {
                    if (selection.isNotEmpty()) SelectionActions()

                    if (verses.isEmpty()) {
                        LightText(
                            text = status ?: "${ref.label()} is not on this device in " +
                                "${translation.abbreviation}.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    } else {
                        verses.forEach { verse ->
                            val key = viewModel.keyOf(verse)
                            VerseBlock(
                                verse = verse,
                                highlighted = marks.isHighlighted(key),
                                selected = key in selection,
                                hasNote = marks.forVerse(key)?.hasNote == true,
                                bookmarked = marks.forVerse(key)?.bookmarked == true,
                                onTap = { viewModel.toggleSelection(key) },
                            )
                        }
                    }

                    // Naming the destination beats PREVIOUS/NEXT: at the end of
                    // a book it is the only thing that says which book is next.
                    val previous = Canon.previous(ref)
                    val next = Canon.next(ref)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp)) {
                        if (previous != null) {
                            LightText(
                                text = previous.label(),
                                variant = LightTextVariant.Button,
                                modifier = Modifier.lightClickable { viewModel.goTo(previous) },
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {}
                        if (next != null) {
                            LightText(
                                text = next.label(),
                                variant = LightTextVariant.Button,
                                modifier = Modifier.lightClickable { viewModel.goTo(next) },
                            )
                        }
                    }
                }

                if (footerVisible) {
                    // Enough top padding that text cut mid-line at the edge of
                    // the scroll region does not read as sitting under the bar.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 14.dp)
                    ) {
                        val previous = Canon.previous(ref)
                        val next = Canon.next(ref)

                        if (previous != null) {
                            FooterAction("‹ ${step(previous, ref)}") {
                                viewModel.goTo(previous)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {}
                        FooterAction("SAVED") { openMarks() }
                        FooterAction("SETTINGS") { openSettings() }
                        Column(modifier = Modifier.weight(1f)) {}
                        if (next != null) {
                            FooterAction("${step(next, ref)} ›") { viewModel.goTo(next) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SelectionActions() {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            LightText(
                text = viewModel.selectionLabel.uppercase(),
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            // Two rows: five labels on one line truncate on this screen rather
            // than wrapping, and a half-visible action reads as a rendering
            // fault.
            Row(modifier = Modifier.fillMaxWidth()) {
                Action("UNDERLINE") { viewModel.highlightSelection() }
                Action("COMPARE") { openCompare() }
                Action("NOTE") { openNote() }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Action("MARK") { viewModel.bookmarkSelection() }
                Action("CLEAR") { viewModel.clearSelection() }
            }
        }
    }

    @Composable
    private fun FooterAction(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            modifier = Modifier
                .lightClickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }

    @Composable
    private fun Action(label: String, onClick: () -> Unit) {
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            modifier = Modifier
                .lightClickable(onClick = onClick)
                .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
        )
    }

    // --- navigation -----------------------------------------------------

    private fun openBooks() {
        navigateTo(::BookSelectScreen) { destination -> viewModel.goTo(destination) }
    }

    private fun openVersions(current: Translation) {
        val reasons = viewModel.blocked.value
        navigateTo({ activity -> VersionSelectScreen(activity, current, reasons) }) { id ->
            Translations.byId(id)?.let { viewModel.switchTo(it) }
        }
    }

    private fun openSettings() {
        navigateTo({ activity -> SettingsScreen(activity, keyStore) })
    }

    private fun openMarks() {
        navigateTo({ activity -> MarksScreen(activity, viewModel.marks) }) { destination ->
            viewModel.goTo(destination)
        }
    }

    private fun openCompare() {
        val verses = viewModel.selectedVerses()
        if (verses.isEmpty()) return
        val readable = viewModel.readable.value
        navigateTo({ activity ->
            CompareScreen(activity, viewModel.repository, verses, readable)
        })
    }

    private fun openNote() {
        val anchor = viewModel.noteAnchor() ?: return
        val title = VerseRef.parse(anchor)?.label() ?: "Note"
        val existing = viewModel.existingNote()
        navigateTo({ activity -> NoteEditScreen(activity, title, existing) }) { text ->
            viewModel.saveNote(text)
        }
    }

    private fun studyWord(word: Word) {
        if (word.strong == null) return
        val testament = viewModel.testament()
        navigateTo(
            { activity -> WordStudyScreen(activity, word, testament, lexicon, concordance) },
        ) { destination -> viewModel.goTo(destination) }
    }

    // --- verse rendering ------------------------------------------------

    @Composable
    private fun VerseBlock(
        verse: Verse,
        highlighted: Boolean,
        selected: Boolean,
        hasNote: Boolean,
        bookmarked: Boolean,
        onTap: () -> Unit,
    ) {
        verse.heading?.let { heading ->
            LightText(
                text = heading,
                variant = LightTextVariant.Subheading,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(bottom = 12.dp)
        ) {
            // A highlight underlines the words themselves; the margin bar is
            // left to mean "selected, not yet acted on". The screen is
            // greyscale, so the two states have to differ in kind rather than
            // in shade - four tones of grey would be four ways of looking the
            // same.
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(end = 1.dp)
                    .then(
                        if (selected) {
                            Modifier.background(LightThemeTokens.colors.contentSecondary)
                        } else {
                            Modifier
                        }
                    )
            )

            // The number keeps its own column. Sharing a line with the text
            // lets a long verse run straight into the next number.
            LightText(
                text = buildString {
                    append(verse.verse)
                    if (bookmarked) append("*")
                    if (hasNote) append("·")
                },
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier
                    .width(34.dp)
                    .padding(start = 6.dp),
            )
            VerseText(verse, highlighted, Modifier.weight(1f), onTap)
        }
    }

    @Composable
    private fun VerseText(
        verse: Verse,
        highlighted: Boolean,
        modifier: Modifier,
        onTap: () -> Unit,
    ) {
        val rendered = remember(verse) { render(verse) }

        if (rendered.words.isEmpty()) {
            // Untagged module - nothing to long-press, but still selectable.
            LightText(
                text = verse.render(),
                variant = LightTextVariant.Paragraph,
                underline = highlighted,
                modifier = modifier.lightClickable(onClick = onTap),
            )
            return
        }

        var layout by remember(verse) { mutableStateOf<TextLayoutResult?>(null) }
        val style = LightThemeTokens.typography.paragraph
            .copy(
                color = LightThemeTokens.colors.content,
                textDecoration = if (highlighted) TextDecoration.Underline else null,
            )

        // One text block rather than a word-per-box layout: the verse still
        // wraps as prose, and the tapped word is found by hit-testing the
        // character offset instead. Tap selects the verse, long-press studies
        // the word under the finger.
        BasicText(
            text = rendered.text,
            style = style,
            onTextLayout = { layout = it },
            modifier = modifier.pointerInput(rendered) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { position ->
                        val result = layout ?: return@detectTapGestures
                        val offset = result.getOffsetForPosition(position)
                        rendered.wordAt(offset)?.let(::studyWord)
                    },
                )
            },
        )
    }

    private companion object {

        /**
         * How far the page must move before the footer reacts, in pixels.
         * Without it, the small jitter of a finger resting on the screen
         * flickers the footer in and out.
         */
        const val SCROLL_THRESHOLD = 24

        /**
         * A compact label for the footer's chapter step.
         *
         * Within a book the number alone is unambiguous, since the book is
         * named in the header directly above. Crossing into another book is
         * the case worth spelling out, and the only one where a bare number
         * would mislead.
         */
        fun step(target: ChapterRef, current: ChapterRef): String =
            if (target.book == current.book) {
                target.chapter.toString()
            } else {
                target.label()
            }

        /** A verse laid out for display, with each word's span remembered. */
        data class Rendered(
            val text: AnnotatedString,
            val spans: List<IntRange>,
            val words: List<Word>,
        ) {
            fun wordAt(offset: Int): Word? {
                val index = spans.indexOfFirst { offset in it }
                return if (index >= 0) words[index] else null
            }
        }

        fun render(verse: Verse): Rendered {
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
    }
}
