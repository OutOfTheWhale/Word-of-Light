package com.outofthewhale.wordoflight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterParserTest {

    private fun bodies(verses: List<Verse>) = verses.map { it.render() }
    private fun numbers(verses: List<Verse>) = verses.map { it.verse }

    // --- bracketed form -------------------------------------------------

    @Test
    fun `bracketed numbers split the chapter`() {
        val verses = ChapterParser.parse("[1] alpha [2] beta [3] gamma", 1)
        assertEquals(listOf(1, 2, 3), numbers(verses))
        assertEquals(listOf("alpha", "beta", "gamma"), bodies(verses))
    }

    @Test
    fun `bracketed numbers are trusted even when they jump`() {
        // Some responses omit verses; the explicit number is authoritative.
        val verses = ChapterParser.parse("[1] alpha [5] beta", 1)
        assertEquals(listOf(1, 5), numbers(verses))
    }

    @Test
    fun `a number in the text is not mistaken for a bracketed verse`() {
        val verses = ChapterParser.parse("[1] he lived 800 years [2] beta", 1)
        assertEquals(listOf(1, 2), numbers(verses))
        assertTrue(bodies(verses).first().contains("800"))
    }

    @Test
    fun `the chapter number is carried onto every verse`() {
        val verses = ChapterParser.parse("[1] alpha [2] beta", 7)
        assertTrue(verses.all { it.chapter == 7 })
    }

    // --- sequential form ------------------------------------------------

    @Test
    fun `bare numbers split the chapter`() {
        val verses = ChapterParser.parse("1 alpha 2 beta 3 gamma", 1)
        assertEquals(listOf(1, 2, 3), numbers(verses))
        assertEquals(listOf("alpha", "beta", "gamma"), bodies(verses))
    }

    @Test
    fun `a chapter opening without a leading 1 still starts at verse 1`() {
        val verses = ChapterParser.parse("alpha 2 beta", 1)
        assertEquals(listOf(1, 2), numbers(verses))
        assertEquals("alpha", bodies(verses).first())
    }

    @Test
    fun `numbers inside the text do not split a verse`() {
        // The trap that shreds genealogies.
        val verses = ChapterParser.parse("1 he was 130 years old and lived 800 years 2 beta", 1)
        assertEquals(listOf(1, 2), numbers(verses))
        assertTrue(bodies(verses).first().contains("130"))
        assertTrue(bodies(verses).first().contains("800"))
    }

    @Test
    fun `only the next expected number opens a verse`() {
        // The 5 appears before the 2 but cannot open verse 5 out of order.
        val verses = ChapterParser.parse("1 alpha 5 loaves 2 beta", 1)
        assertEquals(listOf(1, 2), numbers(verses))
        assertTrue(bodies(verses).first().contains("5 loaves"))
    }

    @Test
    fun `a number glued to a word is never a verse marker`() {
        val verses = ChapterParser.parse("1 alpha psalm2 beta", 1)
        assertEquals(listOf(1), numbers(verses))
    }

    @Test
    fun `a number inside a larger number is never a verse marker`() {
        val verses = ChapterParser.parse("1 alpha 250 beta", 1)
        assertEquals(listOf(1), numbers(verses))
    }

    @Test
    fun `a verse number may be followed by an opening quote`() {
        val verses = ChapterParser.parse("1 alpha 2 “beta”", 1)
        assertEquals(listOf(1, 2), numbers(verses))
    }

    // --- general --------------------------------------------------------

    @Test
    fun `whitespace is collapsed`() {
        val verses = ChapterParser.parse("[1]   alpha\n\n   beta  ", 1)
        assertEquals("alpha beta", bodies(verses).single())
    }

    @Test
    fun `non-breaking spaces are normalised`() {
        val verses = ChapterParser.parse("[1] alpha beta", 1)
        assertEquals("alpha beta", bodies(verses).single())
    }

    @Test
    fun `punctuation stranded by stripped notes is removed`() {
        // Excluding notes removes their bodies but can leave the separator, so
        // a verse comes back ending ". ," where cross references used to be.
        val verses = ChapterParser.parse("[1] alpha beta. , [2] gamma", 1)
        assertEquals(listOf("alpha beta.", "gamma"), bodies(verses))
    }

    @Test
    fun `several stranded marks are all removed`() {
        assertEquals("alpha.", bodies(ChapterParser.parse("[1] alpha. ; ,", 1)).single())
    }

    @Test
    fun `ordinary punctuation is left alone`() {
        // A real comma never has whitespace before it, which is what makes the
        // stranded ones safe to strip.
        val verses = ChapterParser.parse("[1] alpha, beta; gamma: delta", 1)
        assertEquals("alpha, beta; gamma: delta", bodies(verses).single())
    }

    @Test
    fun `empty input yields no verses rather than a blank one`() {
        assertTrue(ChapterParser.parse("", 1).isEmpty())
        assertTrue(ChapterParser.parse("   ", 1).isEmpty())
    }

    @Test
    fun `a verse with no body is dropped`() {
        val verses = ChapterParser.parse("[1] alpha [2]   [3] gamma", 1)
        assertEquals(listOf(1, 3), numbers(verses))
    }

    @Test
    fun `fetched verses carry no tagging`() {
        // Only the bundled KJV is Strong's-tagged; API text is plain, so the
        // reader must fall back to its untagged path rather than long-pressing
        // into an empty word study.
        val verse = ChapterParser.parse("[1] alpha", 1).single()
        assertTrue(verse.words().isEmpty())
        assertEquals("alpha", verse.render())
    }
}
