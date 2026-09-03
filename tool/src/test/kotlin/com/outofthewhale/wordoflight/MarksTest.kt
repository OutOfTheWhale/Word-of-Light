package com.outofthewhale.wordoflight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarksTest {

    private val genesis = "gen.1.1"
    private val exodus = "exo.1.1"

    @Test
    fun `a highlight is remembered`() {
        val marks = Marks().withHighlight(listOf(genesis), true)
        assertTrue(marks.isHighlighted(genesis))
    }

    @Test
    fun `an unmarked verse is not highlighted`() {
        assertFalse(Marks().isHighlighted(genesis))
    }

    @Test
    fun `highlighting a selection marks every verse in it`() {
        val marks = Marks().withHighlight(listOf(genesis, exodus), true)
        assertTrue(marks.isHighlighted(genesis))
        assertTrue(marks.isHighlighted(exodus))
    }

    @Test
    fun `toggling a partly highlighted selection turns all of it on`() {
        // Matching how selection tools normally behave: the first press
        // completes the set rather than inverting each verse individually.
        val marks = Marks()
            .withHighlight(listOf(genesis), true)
            .toggleHighlight(listOf(genesis, exodus))
        assertTrue(marks.isHighlighted(genesis))
        assertTrue(marks.isHighlighted(exodus))
    }

    @Test
    fun `toggling a fully highlighted selection clears it`() {
        val marks = Marks()
            .withHighlight(listOf(genesis, exodus), true)
            .toggleHighlight(listOf(genesis, exodus))
        assertFalse(marks.isHighlighted(genesis))
        assertFalse(marks.isHighlighted(exodus))
    }

    @Test
    fun `toggling an empty selection changes nothing`() {
        val before = Marks().withHighlight(listOf(genesis), true)
        assertEquals(before, before.toggleHighlight(emptyList()))
    }

    @Test
    fun `removing the last mark drops the record entirely`() {
        // Otherwise the store slowly fills with blank entries that still show
        // up in counts.
        val marks = Marks()
            .withHighlight(listOf(genesis), true)
            .withHighlight(listOf(genesis), false)
        assertTrue(marks.verses.isEmpty())
    }

    @Test
    fun `a highlight and a note coexist on one verse`() {
        val marks = Marks()
            .withHighlight(listOf(genesis), true)
            .withNote(genesis, "a thought")
        assertTrue(marks.isHighlighted(genesis))
        assertEquals("a thought", marks.noteFor(genesis))
        assertEquals(1, marks.verses.size)
    }

    @Test
    fun `clearing a note leaves the highlight alone`() {
        val marks = Marks()
            .withHighlight(listOf(genesis), true)
            .withNote(genesis, "a thought")
            .withNote(genesis, "")
        assertTrue(marks.isHighlighted(genesis))
        assertEquals("", marks.noteFor(genesis))
    }

    @Test
    fun `a note is trimmed`() {
        assertEquals("a thought", Marks().withNote(genesis, "  a thought  ").noteFor(genesis))
    }

    @Test
    fun `a blank note on an otherwise unmarked verse stores nothing`() {
        assertTrue(Marks().withNote(genesis, "   ").verses.isEmpty())
    }

    @Test
    fun `a bookmark toggles`() {
        val on = Marks().toggleBookmark(genesis)
        assertTrue(on.forVerse(genesis)?.bookmarked == true)
        assertNull(on.toggleBookmark(genesis).forVerse(genesis))
    }

    @Test
    fun `clear removes every mark on a verse at once`() {
        val marks = Marks()
            .withHighlight(listOf(genesis), true)
            .withNote(genesis, "a thought")
            .toggleBookmark(genesis)
        assertTrue(marks.clear(genesis).verses.isEmpty())
    }

    @Test
    fun `the lists only report what was actually set`() {
        val marks = Marks()
            .withHighlight(listOf(genesis), true)
            .withNote(exodus, "a thought")
        assertEquals(listOf(genesis), marks.highlights.map { it.ref })
        assertEquals(listOf(exodus), marks.notes.map { it.ref })
        assertTrue(marks.bookmarks.isEmpty())
    }

    @Test
    fun `saved verses sort in canonical order, not alphabetical`() {
        // "exo" sorts before "gen" as a string, but Exodus follows Genesis.
        val marks = Marks().withHighlight(listOf(exodus, genesis), true)
        assertEquals(listOf(genesis, exodus), marks.highlights.map { it.ref })
    }

    @Test
    fun `verses within a book sort by chapter then verse`() {
        val refs = listOf("gen.2.1", "gen.1.10", "gen.1.2")
        val marks = Marks().withHighlight(refs, true)
        assertEquals(
            listOf("gen.1.2", "gen.1.10", "gen.2.1"),
            marks.highlights.map { it.ref },
        )
    }

    @Test
    fun `a chapter note is kept and cleared`() {
        val ref = ChapterRef("gen", 1)
        val marks = Marks().withChapterNote(ref, "on the whole chapter")
        assertEquals("on the whole chapter", marks.chapterNote(ref))
        assertEquals("", marks.withChapterNote(ref, "").chapterNote(ref))
    }

    // --- chapter bookmarks ----------------------------------------------

    @Test
    fun `a chapter bookmark toggles`() {
        val ref = ChapterRef("gen", 1)
        val on = Marks().toggleChapterBookmark(ref)
        assertTrue(on.isChapterBookmarked(ref))
        assertFalse(on.toggleChapterBookmark(ref).isChapterBookmarked(ref))
    }

    @Test
    fun `a chapter bookmark is separate from a verse bookmark`() {
        // Flagging a chapter should not make its verses look bookmarked.
        val marks = Marks().toggleChapterBookmark(ChapterRef("gen", 1))
        assertTrue(marks.bookmarks.isEmpty())
        assertTrue(marks.isChapterBookmarked(ChapterRef("gen", 1)))
    }

    @Test
    fun `bookmarked chapters sort in canonical order`() {
        val marks = Marks()
            .toggleChapterBookmark(ChapterRef("exo", 1))
            .toggleChapterBookmark(ChapterRef("gen", 2))
            .toggleChapterBookmark(ChapterRef("gen", 10))
        assertEquals(
            listOf("gen.2", "gen.10", "exo.1"),
            marks.bookmarkedChapters.map { it.key() },
        )
    }

    // --- reading history -------------------------------------------------

    @Test
    fun `the last chapter opened is where reading resumes`() {
        val marks = Marks()
            .withVisit(ChapterRef("gen", 1), "kjv", 1)
            .withVisit(ChapterRef("jhn", 3), "csb", 2)
        val resume = marks.lastRead
        assertEquals("jhn.3", resume?.ref)
        assertEquals("csb", resume?.translation)
    }

    @Test
    fun `nothing read yet means nowhere to resume`() {
        assertNull(Marks().lastRead)
    }

    @Test
    fun `reopening a chapter moves it up rather than repeating it`() {
        // The history is of places, not of taps.
        val marks = Marks()
            .withVisit(ChapterRef("gen", 1), "kjv", 1)
            .withVisit(ChapterRef("jhn", 3), "kjv", 2)
            .withVisit(ChapterRef("gen", 1), "kjv", 3)
        assertEquals(listOf("gen.1", "jhn.3"), marks.recents.map { it.ref })
    }

    @Test
    fun `the history is capped`() {
        val marks = (1..60).fold(Marks()) { acc, chapter ->
            acc.withVisit(ChapterRef("psa", chapter), "kjv", chapter.toLong())
        }
        assertTrue(marks.recents.size <= 40, "was ${marks.recents.size}")
        assertEquals("psa.60", marks.recents.first().ref)
    }

    @Test
    fun `a chapter reference round-trips through its stored form`() {
        val ref = ChapterRef("1sa", 17)
        assertEquals(ref, ChapterRef.parse(ref.key()))
    }

    @Test
    fun `a malformed chapter reference parses to nothing`() {
        assertNull(ChapterRef.parse("gen"))
        assertNull(ChapterRef.parse("gen.x"))
        assertNull(ChapterRef.parse(""))
    }

    @Test
    fun `an unparseable reference sorts last instead of crashing`() {
        val marks = Marks().withHighlight(listOf("nonsense", genesis), true)
        assertEquals(listOf(genesis, "nonsense"), marks.highlights.map { it.ref })
    }
}
