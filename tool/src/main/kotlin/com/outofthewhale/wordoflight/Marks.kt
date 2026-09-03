package com.outofthewhale.wordoflight

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the reader has done to one verse.
 *
 * Highlight, bookmark and note live on a single record rather than three
 * parallel lists, because they are not mutually exclusive - a verse can easily
 * be highlighted and annotated - and keeping them together means there is only
 * ever one place a given verse appears.
 */
@Serializable
data class VerseMark(
    val ref: String,
    val highlighted: Boolean = false,
    val bookmarked: Boolean = false,
    val note: String = "",
    val updatedAt: Long = 0L,
) {
    /** Nothing left to remember; the record should be dropped rather than stored blank. */
    val isEmpty: Boolean
        get() = !highlighted && !bookmarked && note.isBlank()

    val hasNote: Boolean get() = note.isNotBlank()

    fun verseRef(): VerseRef? = VerseRef.parse(ref)
}

/**
 * Everything the reader has saved.
 *
 * Small enough to hold as one document - a lifetime of highlights is thousands
 * of records, not millions - so it is stored as a single JSON value rather than
 * a database, and every change is a pure transformation of this object.
 */
/**
 * A chapter the reader has opened.
 *
 * This is how "pick up where you left off" works: every chapter opened is
 * recorded with the translation it was read in, most recent first, and the
 * newest entry is the resume point. Keeping a list rather than a single
 * position also answers "what was I reading last week", which one value cannot.
 */
@Serializable
data class Recent(
    val ref: String,
    val translation: String = "kjv",
    val at: Long = 0L,
) {
    fun chapterRef(): ChapterRef? = ChapterRef.parse(ref)
}

/**
 * A note as it should be listed: one thought, however many verses carry it.
 *
 * Not stored - derived from the verse records, which each hold their own copy
 * so that any verse in the passage shows the note marker.
 */
data class NoteEntry(
    val first: VerseRef,
    val lastVerse: Int,
    val note: String,
) {
    val label: String
        get() = if (lastVerse == first.verse) first.label() else "${first.label()}-$lastVerse"

    fun chapterRef(): ChapterRef = first.chapterRef()
}

@Serializable
data class Marks(
    val verses: Map<String, VerseMark> = emptyMap(),
    val chapterNotes: Map<String, String> = emptyMap(),
    /** Whole chapters flagged, as distinct from single verses. */
    val chapterBookmarks: Set<String> = emptySet(),
    val recents: List<Recent> = emptyList(),
) {
    fun forVerse(ref: String): VerseMark? = verses[ref]

    fun isHighlighted(ref: String): Boolean = verses[ref]?.highlighted == true

    fun noteFor(ref: String): String = verses[ref]?.note.orEmpty()

    fun chapterNote(ref: ChapterRef): String = chapterNotes[ref.key()].orEmpty()

    val highlights: List<VerseMark>
        get() = verses.values.filter { it.highlighted }.sortedBy { it.ref.canonicalOrder() }

    val bookmarks: List<VerseMark>
        get() = verses.values.filter { it.bookmarked }.sortedBy { it.ref.canonicalOrder() }

    val notes: List<VerseMark>
        get() = verses.values.filter { it.hasNote }.sortedBy { it.ref.canonicalOrder() }

    /**
     * Notes for display, with a run of verses sharing one note folded into a
     * single entry - "Genesis 1:1-5" rather than the same sentence five times.
     *
     * Only an unbroken run in the same chapter with identical text is merged;
     * two verses that happen to carry the same words separately stay separate,
     * because they were written as separate notes.
     */
    val noteEntries: List<NoteEntry>
        get() {
            val entries = mutableListOf<NoteEntry>()
            notes.forEach { mark ->
                val ref = mark.verseRef() ?: return@forEach
                val last = entries.lastOrNull()
                val continues = last != null &&
                    last.note == mark.note &&
                    last.first.book == ref.book &&
                    last.first.chapter == ref.chapter &&
                    last.lastVerse + 1 == ref.verse
                if (continues) {
                    entries[entries.lastIndex] = last!!.copy(lastVerse = ref.verse)
                } else {
                    entries += NoteEntry(ref, ref.verse, mark.note)
                }
            }
            return entries
        }

    // --- transformations ------------------------------------------------
    // Pure, so they can be tested without a device.

    private fun change(ref: String, now: Long, edit: (VerseMark) -> VerseMark): Marks {
        val existing = verses[ref] ?: VerseMark(ref)
        val updated = edit(existing).copy(updatedAt = now)
        val next = verses.toMutableMap()
        if (updated.isEmpty) next.remove(ref) else next[ref] = updated
        return copy(verses = next)
    }

    fun withHighlight(refs: Collection<String>, on: Boolean, now: Long = 0L): Marks =
        refs.fold(this) { marks, ref ->
            marks.change(ref, now) { it.copy(highlighted = on) }
        }

    /** Highlights the whole selection unless all of it already is, then clears it. */
    fun toggleHighlight(refs: Collection<String>, now: Long = 0L): Marks {
        if (refs.isEmpty()) return this
        val allOn = refs.all { isHighlighted(it) }
        return withHighlight(refs, !allOn, now)
    }

    fun withBookmark(ref: String, on: Boolean, now: Long = 0L): Marks =
        change(ref, now) { it.copy(bookmarked = on) }

    fun toggleBookmark(ref: String, now: Long = 0L): Marks =
        withBookmark(ref, verses[ref]?.bookmarked != true, now)

    fun withNote(ref: String, note: String, now: Long = 0L): Marks =
        change(ref, now) { it.copy(note = note.trim()) }

    /**
     * Puts one note on every verse of a selection.
     *
     * A thought about a passage belongs to the whole passage, so each verse
     * carries it and each shows the note marker. [noteEntries] folds a run
     * back into a single line so the Notes list does not repeat it.
     */
    fun withNoteOn(refs: Collection<String>, note: String, now: Long = 0L): Marks =
        refs.fold(this) { marks, ref -> marks.withNote(ref, note, now) }

    fun withChapterNote(ref: ChapterRef, note: String): Marks {
        val next = chapterNotes.toMutableMap()
        if (note.isBlank()) next.remove(ref.key()) else next[ref.key()] = note.trim()
        return copy(chapterNotes = next)
    }

    /** Removes every mark on a verse in one go. */
    fun clear(ref: String): Marks = copy(verses = verses - ref)

    // --- chapters -------------------------------------------------------

    fun isChapterBookmarked(ref: ChapterRef): Boolean = ref.key() in chapterBookmarks

    fun toggleChapterBookmark(ref: ChapterRef): Marks {
        val key = ref.key()
        return copy(
            chapterBookmarks = if (key in chapterBookmarks) {
                chapterBookmarks - key
            } else {
                chapterBookmarks + key
            }
        )
    }

    /** Bookmarked chapters in canonical order. */
    val bookmarkedChapters: List<ChapterRef>
        get() = chapterBookmarks.mapNotNull(ChapterRef::parse)
            .sortedBy { it.canonicalOrder() }

    val annotatedChapters: List<ChapterRef>
        get() = chapterNotes.keys.mapNotNull(ChapterRef::parse)
            .sortedBy { it.canonicalOrder() }

    // --- reading history ------------------------------------------------

    /**
     * Records a chapter as read.
     *
     * The same chapter reopened moves to the front rather than appearing
     * twice, so the list stays a history of *places* rather than of taps. It
     * is capped, because this is for finding your way back, not for keeping a
     * permanent record.
     */
    fun withVisit(ref: ChapterRef, translation: String, now: Long): Marks {
        val key = ref.key()
        val entry = Recent(key, translation, now)
        val kept = recents.filterNot { it.ref == key }
        return copy(recents = (listOf(entry) + kept).take(RECENTS_LIMIT))
    }

    /** Where to resume: the last chapter opened, with the translation used. */
    val lastRead: Recent? get() = recents.firstOrNull()
}

// At file level, not in a companion: @Serializable puts serializer() on the
// companion, so declaring a private one hides it from MarksStore.
private const val RECENTS_LIMIT = 40

/** Sorts a chapter into canonical order rather than alphabetical. */
private fun ChapterRef.canonicalOrder(): Long {
    val index = Canon.books.indexOfFirst { it.id == book }
    if (index < 0) return Long.MAX_VALUE
    return index * 1_000L + chapter
}

/** Sorts "gen.1.1" into canonical order rather than alphabetical. */
private fun String.canonicalOrder(): Long {
    val parsed = VerseRef.parse(this) ?: return Long.MAX_VALUE
    val book = Canon.books.indexOfFirst { it.id == parsed.book }
    if (book < 0) return Long.MAX_VALUE
    return book * 1_000_000L + parsed.chapter * 1_000L + parsed.verse
}

fun VerseRef.key(): String = "$book.$chapter.$verse"

/**
 * Persists [Marks] in the tool's own DataStore.
 *
 * A corrupt or absent value reads back as empty rather than throwing - losing
 * highlights is bad, but refusing to open the Bible at all is worse.
 */
class MarksStore(private val dataStore: DataStore<Preferences>) {

    private val json = Json { ignoreUnknownKeys = true }

    val marks: Flow<Marks> = dataStore.data.map { preferences -> decode(preferences[KEY]) }

    suspend fun update(transform: (Marks) -> Marks) {
        dataStore.edit { preferences ->
            val current = decode(preferences[KEY])
            preferences[KEY] = json.encodeToString(Marks.serializer(), transform(current))
        }
    }

    private fun decode(raw: String?): Marks {
        if (raw.isNullOrBlank()) return Marks()
        return try {
            json.decodeFromString(Marks.serializer(), raw)
        } catch (e: Exception) {
            Marks()
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("marks")
    }
}
