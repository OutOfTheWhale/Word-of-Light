package com.outofthewhale.wordoflight

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Chapters already fetched from a publisher, kept on the phone.
 *
 * Fetched once, read forever after. This is what makes an API-backed
 * translation usable on a phone that is meant to be left in a pocket: after
 * the first read a chapter costs nothing, needs no signal, and does not count
 * against a monthly quota. There are 1,189 chapters in the Bible and 5,000
 * calls a month on the free tier, so ordinary reading caches itself.
 *
 * Cached per chapter rather than per book, because that is the unit the API
 * serves and the unit the reader displays.
 *
 * Note: how much text a publisher permits you to store locally is a question
 * of their terms, not of this code.
 */
class ChapterCache(private val filesDir: File) {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(Verse.serializer())

    fun get(translation: String, ref: ChapterRef): List<Verse>? {
        val file = fileFor(translation, ref)
        if (!file.isFile) return null
        return try {
            json.decodeFromString(serializer, file.readText()).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            // A half-written or stale-format entry should look like a miss, so
            // the next read simply fetches it again.
            file.delete()
            null
        }
    }

    fun put(translation: String, ref: ChapterRef, verses: List<Verse>) {
        if (verses.isEmpty()) return
        val file = fileFor(translation, ref)
        file.parentFile?.mkdirs()
        try {
            file.writeText(json.encodeToString(serializer, verses))
        } catch (e: Exception) {
            // Losing a cache write is not worth failing a read the user can see.
        }
    }

    fun has(translation: String, ref: ChapterRef): Boolean = fileFor(translation, ref).isFile

    /** Whether anything at all has been cached for a translation. */
    fun hasAny(translation: String): Boolean =
        File(File(filesDir, CACHE_DIR), translation).listFiles()?.isNotEmpty() == true

    fun clear(translation: String) {
        File(File(filesDir, CACHE_DIR), translation).deleteRecursively()
    }

    private fun fileFor(translation: String, ref: ChapterRef) =
        File(File(File(filesDir, CACHE_DIR), translation), "${ref.book}.${ref.chapter}.json")

    private companion object {
        const val CACHE_DIR = "cache"
    }
}
