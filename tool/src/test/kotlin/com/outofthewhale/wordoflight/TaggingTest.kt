package com.outofthewhale.wordoflight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaggingTest {

    @Test
    fun `strip removes strongs numbers`() {
        assertEquals("alpha beta", Tagging.strip("alpha[G1] beta[G2]"))
    }

    @Test
    fun `strip removes italic markers but keeps the words`() {
        assertEquals("alpha be beta", Tagging.strip("alpha <em>be</em> beta"))
    }

    @Test
    fun `strip collapses the whitespace left behind`() {
        assertEquals("alpha beta", Tagging.strip("alpha[G1]   <em></em>  beta"))
    }

    @Test
    fun `parse keeps punctuation on the word`() {
        assertEquals("alpha,", Tagging.parse("alpha,[G1]").single().text)
    }

    @Test
    fun `parse reads the strongs number off each word`() {
        val words = Tagging.parse("alpha[G2455] beta[H7225]")
        assertEquals(listOf("G2455", "H7225"), words.map { it.strong })
    }

    @Test
    fun `an untagged word has no strongs number`() {
        assertNull(Tagging.parse("alpha").single().strong)
    }

    @Test
    fun `a word wrapped in em is marked as added`() {
        assertTrue(Tagging.parse("<em>be</em>").single().added)
    }

    @Test
    fun `words outside em are not marked as added`() {
        val words = Tagging.parse("alpha <em>be</em> beta")
        assertEquals(listOf(false, true, false), words.map { it.added })
    }

    @Test
    fun `em spanning several words marks all of them`() {
        // The opening and closing tags sit on different tokens, so the italic
        // state has to survive across the gap.
        val words = Tagging.parse("alpha <em>shall surely be</em> beta")
        assertEquals(listOf(false, true, true, true, false), words.map { it.added })
    }

    @Test
    fun `an added word can also carry a strongs number`() {
        val word = Tagging.parse("<em>be</em>[G1510]").single()
        assertTrue(word.added)
        assertEquals("G1510", word.strong)
    }

    @Test
    fun `bare drops trailing punctuation for matching`() {
        assertEquals("God", Tagging.parse("God,[G2316]").single().bare)
    }

    @Test
    fun `bare leaves an unpunctuated word alone`() {
        assertEquals("God", Tagging.parse("God[G2316]").single().bare)
    }

    @Test
    fun `hebrew belongs to the old testament`() {
        assertTrue(Tagging.isConsistent("H7225", Testament.OLD))
        assertFalse(Tagging.isConsistent("H7225", Testament.NEW))
    }

    @Test
    fun `greek belongs to the new testament`() {
        assertTrue(Tagging.isConsistent("G2316", Testament.NEW))
        assertFalse(Tagging.isConsistent("G2316", Testament.OLD))
    }

    @Test
    fun `a malformed strongs number is never consistent`() {
        assertFalse(Tagging.isConsistent("X1", Testament.OLD))
        assertFalse(Tagging.isConsistent("", Testament.NEW))
    }

    @Test
    fun `a verse line reports its own words`() {
        val line = VerseLine(tagged = "alpha[G1] beta[G2]")
        assertEquals(listOf("G1", "G2"), line.words().map { it.strong })
        assertEquals("alpha beta", line.display)
    }

    @Test
    fun `an untagged line falls back to its plain text`() {
        val line = VerseLine(text = "alpha beta")
        assertTrue(line.words().isEmpty())
        assertEquals("alpha beta", line.display)
    }
}
