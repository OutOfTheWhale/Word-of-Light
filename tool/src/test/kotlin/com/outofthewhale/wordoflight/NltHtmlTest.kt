package com.outofthewhale.wordoflight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Written against the markup Tyndale actually returns, with placeholder words
 * standing in for the text itself.
 */
class NltHtmlTest {

    private fun bodies(verses: List<Verse>) = verses.map { it.render() }
    private fun numbers(verses: List<Verse>) = verses.map { it.verse }

    /** A chapter as it arrives: full document, heading, then verse spans. */
    private fun document(body: String) = """
        <!DOCTYPE html><html lang="en-US"><head><title>NLT API</title></head><body>
        <div id="bibletext" class=" NLT NLT BibleText section"><section>
        <h2 class="bk_ch_vs_header">Placeholder 1:1-3, NLT</h2>
        <verse_export orig="plac_1_1" bk="plac" ch="1" vn="1">
        <h2 class="chapter-number"><span class="cw">Placeholder</span> <span class="cw_ch">1</span></h2>
        $body
        </section></div></body></html>
    """.trimIndent()

    @Test
    fun `each verse is found once`() {
        val html = document(
            """<span class="vn">1</span>alpha</verse_export>
               <verse_export vn="2"><span class="vn">2</span>beta</verse_export>
               <verse_export vn="3"><span class="vn">3</span>gamma</verse_export>"""
        )
        val verses = NltHtml.parse(html, 1)
        assertEquals(listOf(1, 2, 3), numbers(verses))
        assertEquals(listOf("alpha", "beta", "gamma"), bodies(verses))
    }

    @Test
    fun `the chapter heading does not become a phantom verse`() {
        // Matching both the verse_export wrapper and the vn span split every
        // verse twice, turning the heading between them into an extra verse 1.
        val html = document("""<span class="vn">1</span>alpha</verse_export>""")
        val verses = NltHtml.parse(html, 1)
        assertEquals(1, verses.size)
        assertEquals("alpha", verses.single().render())
    }

    @Test
    fun `the document head is not part of the first verse`() {
        val body = NltHtml.parse(document("""<span class="vn">1</span>alpha"""), 1).single().render()
        assertTrue("NLT API" !in body, body)
        assertTrue("Placeholder" !in body, body)
    }

    @Test
    fun `the verse number does not leak into the text`() {
        val verse = NltHtml.parse(document("""<span class="vn">1</span>alpha"""), 1).single()
        assertEquals("alpha", verse.render())
    }

    @Test
    fun `a poetry span whose class merely contains vn is not a verse marker`() {
        val html = document(
            """<span class="vn">1</span>alpha
               <span class="ext-poet1-vn-sp">2</span>beta"""
        )
        assertEquals(listOf(1), numbers(NltHtml.parse(html, 1)))
    }

    // --- notes ----------------------------------------------------------

    @Test
    fun `translator notes are removed with their contents`() {
        val html = document(
            """<span class="vn">1</span>alpha<span class="tn">a note</span> beta"""
        )
        assertEquals("alpha beta", NltHtml.parse(html, 1).single().render())
    }

    @Test
    fun `nested notes are removed from the inside out`() {
        val html = document(
            """<span class="vn">1</span>alpha<span class="tn"><span class="tn-ref">ref</span> a note</span> beta"""
        )
        assertEquals("alpha beta", NltHtml.parse(html, 1).single().render())
    }

    @Test
    fun `a note containing formatting tags is removed whole`() {
        // Why peeling by pattern stalled: a note body is not flat, so a regex
        // requiring tag-free content never matches the outer element.
        val html = document(
            """<span class="vn">1</span>alpha<span class="tn"><span class="tn-ref">1:1</span> Or <em>another reading</em></span> beta"""
        )
        assertEquals("alpha beta", NltHtml.parse(html, 1).single().render())
    }

    @Test
    fun `text after a note is kept`() {
        val html = document(
            """<span class="vn">1</span>alpha<span class="tn">a <em>note</em></span> beta gamma"""
        )
        assertEquals("alpha beta gamma", NltHtml.parse(html, 1).single().render())
    }

    @Test
    fun `the note marker anchor is removed`() {
        val html = document("""<span class="vn">1</span>alpha<a class="a-tn">*</a> beta""")
        assertEquals("alpha beta", NltHtml.parse(html, 1).single().render())
    }

    @Test
    fun `punctuation stranded by a removed note is cleaned up`() {
        val html = document("""<span class="vn">1</span>alpha. <span class="tn">note</span> ,""")
        assertEquals("alpha.", NltHtml.parse(html, 1).single().render())
    }

    // --- headings -------------------------------------------------------

    @Test
    fun `a subhead becomes the verse heading rather than its text`() {
        val html = document(
            """<span class="vn">1</span>alpha</verse_export>
               <verse_export vn="2"><h3 class="subhead">A Section</h3><span class="vn">2</span>beta"""
        )
        val verses = NltHtml.parse(html, 1)
        assertEquals("A Section", verses.first { it.verse == 2 }.heading)
        assertEquals("beta", verses.first { it.verse == 2 }.render())
    }

    @Test
    fun `a subhead opening the chapter lands on verse 1`() {
        // It sits before the first verse marker, in the region that is
        // otherwise discarded along with the document head.
        val html = document("""<h3 class="subhead">A Section</h3><span class="vn">1</span>alpha""")
        val verse = NltHtml.parse(html, 1).single()
        assertEquals("A Section", verse.heading)
        assertEquals("alpha", verse.render())
    }

    @Test
    fun `a verse without a subhead has no heading`() {
        assertNull(NltHtml.parse(document("""<span class="vn">1</span>alpha"""), 1).single().heading)
    }

    // --- general --------------------------------------------------------

    @Test
    fun `entities are decoded`() {
        val html = document("""<span class="vn">1</span>alpha &amp; beta&#39;s &quot;gamma&quot;""")
        assertEquals("alpha & beta's \"gamma\"", NltHtml.parse(html, 1).single().render())
    }

    @Test
    fun `an escaped ampersand cannot revive a second entity`() {
        // "&amp;#39;" must stay literal rather than becoming an apostrophe.
        val html = document("""<span class="vn">1</span>alpha &amp;#39; beta""")
        assertEquals("alpha &#39; beta", NltHtml.parse(html, 1).single().render())
    }

    @Test
    fun `markup with no verse spans falls back to reading it as text`() {
        val html = "<html><body><p>1 alpha 2 beta</p></body></html>"
        assertEquals(listOf(1, 2), numbers(NltHtml.parse(html, 1)))
    }

    @Test
    fun `an empty document yields no verses`() {
        assertTrue(NltHtml.parse("", 1).isEmpty())
    }

    @Test
    fun `fetched verses carry no tagging`() {
        // Only the bundled KJV is Strong's-tagged, so the reader must fall back
        // to its untagged path rather than long-pressing into an empty study.
        assertTrue(NltHtml.parse(document("""<span class="vn">1</span>alpha"""), 1)
            .single().words().isEmpty())
    }
}
