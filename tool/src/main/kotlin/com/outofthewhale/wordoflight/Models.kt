package com.outofthewhale.wordoflight

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where in Scripture the reader is currently sitting. */
@Serializable
data class ChapterRef(
    val book: String,
    val chapter: Int,
) {
    fun label(): String = "${Canon.book(book)?.name ?: book} $chapter"

    /** The stored form, `"gen.1"`. */
    fun key(): String = "$book.$chapter"

    companion object {
        fun parse(raw: String): ChapterRef? {
            val parts = raw.split('.')
            if (parts.size != 2) return null
            val chapter = parts[1].toIntOrNull() ?: return null
            return ChapterRef(parts[0], chapter)
        }
    }
}

/**
 * One line of a verse.
 *
 * Poetry is not a styling flourish - Hebrew poetry is built on parallel lines,
 * and running them together as prose destroys the structure the passage is
 * carrying. So the line break is data, and it survives the import.
 */
@Serializable
data class VerseLine(
    val text: String = "",
    val poetry: Boolean = false,
    /**
     * The same line with Strong's markers still in it, e.g.
     * `"Jude,[G2455] the servant[G1401]"`, plus `<em>` around words the
     * translators supplied.
     *
     * Only tagged modules carry this, and when they do [text] is left empty
     * rather than storing the words twice - the KJV runs to ~790,000 words, so
     * the duplication would cost several megabytes of APK for nothing. Read
     * [display] instead of either field.
     */
    val tagged: String? = null,
) {
    /** Readable text, whichever way this line was stored. */
    val display: String
        get() = tagged?.let { Tagging.strip(it) } ?: text

    /** Words with their Strong's numbers; empty when the line is untagged. */
    fun words(): List<Word> = tagged?.let { Tagging.parse(it) } ?: emptyList()
}

@Serializable
data class Verse(
    val chapter: Int,
    val verse: Int,
    /** Section heading introducing this verse, when the source had one. */
    val heading: String? = null,
    val lines: List<VerseLine> = emptyList(),
) {
    /** Flattened for display; poetry lines keep their breaks. */
    fun render(): String = buildString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append(if (line.poetry) '\n' else ' ')
            append(line.display)
        }
    }

    /** Every tagged word in the verse, in order. */
    fun words(): List<Word> = lines.flatMap { it.words() }
}

/**
 * One book of one translation - the unit the app loads, caches and ships.
 *
 * This is exactly the shape the importer emits, so a converted book drops
 * straight in with no second format in between.
 */
@Serializable
data class BookModule(
    @SerialName("book") val bookName: String,
    val translation: String,
    val verses: List<Verse> = emptyList(),
) {
    fun chapter(number: Int): List<Verse> = verses.filter { it.chapter == number }

    val chapterCount: Int get() = verses.maxOfOrNull { it.chapter } ?: 0
}

/**
 * A translation the reader can switch to.
 *
 * [Source] is the whole point of the design: the reader does not care where
 * text comes from. Bundled, sideloaded, downloaded or fetched from a
 * publisher's API - it all arrives as a [BookModule] and renders identically.
 * That is what keeps the licensing question out of the UI layer entirely.
 */
enum class Source {
    /** Shipped inside the APK. Public domain only. Always available offline. */
    BUNDLED,

    /** Copied onto the device by the user. Never leaves the phone. */
    SIDELOADED,

    /** Fetched from a publisher API, then cached locally. */
    API,
}

/**
 * Which service supplies a translation.
 *
 * Three providers, three different endpoints and auth schemes. Crossway and
 * Tyndale each run their own API for their own translation; everything else
 * comes through American Bible Society's API.Bible, whose free tier allows
 * exactly three copyrighted translations - hence CSB, NKJV and AMP and no
 * more without paying.
 */
enum class ApiProvider(
    val displayName: String,
    val keyUrl: String,
    /** False while no client for this provider has been written yet. */
    val supported: Boolean,
    /**
     * Whether a remote id has to be discovered before anything can be read.
     * API.Bible identifies each translation by an opaque id that differs per
     * account; Tyndale and Crossway address theirs by name.
     */
    val needsBinding: Boolean,
) {
    ESV_API("Crossway", "https://api.esv.org", supported = false, needsBinding = false),
    NLT_API("Tyndale", "https://api.nlt.to", supported = true, needsBinding = false),
    API_BIBLE("API.Bible", "https://docs.api.bible", supported = true, needsBinding = true),
}

data class Translation(
    val id: String,
    val abbreviation: String,
    val name: String,
    val source: Source,
    val provider: ApiProvider? = null,
) {
    val isOffline: Boolean get() = source != Source.API

    /** True once the key this translation needs has been entered. */
    fun isReady(keys: Set<ApiProvider>): Boolean =
        provider == null || provider in keys

    /**
     * Why this translation cannot be read, or null when it can.
     *
     * Saying "add an API key" to someone who has already added one is worse
     * than saying nothing, so an unbuilt client says so plainly.
     */
    fun blockedReason(keys: Set<ApiProvider>, bindings: Set<String>): String? = when {
        provider == null -> null
        !provider.supported -> "not supported yet"
        provider !in keys -> "add a ${provider.displayName} key in Settings"
        provider.needsBinding && id !in bindings -> "open Settings to finish setup"
        else -> null
    }
}

object Translations {
    val KJV = Translation("kjv", "KJV", "King James Version", Source.BUNDLED)

    // Publishers running their own API. These do not consume an API.Bible slot.
    val ESV = Translation("esv", "ESV", "English Standard Version",
        Source.API, ApiProvider.ESV_API)
    val NLT = Translation("nlt", "NLT", "New Living Translation",
        Source.API, ApiProvider.NLT_API)

    // The three API.Bible slots.
    val CSB = Translation("csb", "CSB", "Christian Standard Bible",
        Source.API, ApiProvider.API_BIBLE)
    val NKJV = Translation("nkjv", "NKJV", "New King James Version",
        Source.API, ApiProvider.API_BIBLE)
    val AMP = Translation("amp", "AMP", "Amplified Bible",
        Source.API, ApiProvider.API_BIBLE)

    val all: List<Translation> = listOf(KJV, ESV, NLT, CSB, NKJV, AMP)

    fun byId(id: String): Translation? = all.firstOrNull { it.id == id }

    /** How many API.Bible slots the current line-up uses, of the three free ones. */
    val apiBibleSlotsUsed: Int
        get() = all.count { it.provider == ApiProvider.API_BIBLE }
}
