package com.outofthewhale.wordoflight

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads books of Scripture, without caring where they came from.
 *
 * Two places are searched, in order:
 *
 *   1. `filesDir/modules/<translation>/<book>.json` - anything put on the
 *      device: a book you converted and copied across, or a chapter cached
 *      after an API fetch.
 *   2. `assets/bible/<translation>/<book>.json` - what shipped in the APK.
 *      Public domain only, and always present.
 *
 * Local wins, so a fuller copy of a book shadows the bundled one without
 * needing a rebuild.
 */
class ModuleStore(
    private val filesDir: File,
    private val readAsset: (String) -> ByteArray,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, BookModule?>()

    private fun localFile(translation: String, book: String) =
        File(File(File(filesDir, MODULES_DIR), translation), "$book.json")

    private fun assetPath(translation: String, book: String) =
        "$ASSETS_DIR/$translation/$book.json"

    /** Null when this translation has no copy of this book on the device. */
    fun load(translation: String, book: String): BookModule? =
        cache.getOrPut("$translation/$book") {
            readLocal(translation, book) ?: readBundled(translation, book)
        }

    private fun readLocal(translation: String, book: String): BookModule? {
        val file = localFile(translation, book)
        if (!file.isFile) return null
        return decode(file.readBytes(), "file ${file.path}")
    }

    private fun readBundled(translation: String, book: String): BookModule? {
        val path = assetPath(translation, book)
        val bytes = try {
            readAsset(path)
        } catch (e: Exception) {
            // A missing asset is the normal case, not a failure - most
            // translations ship no books at all.
            return null
        }
        return decode(bytes, "asset $path")
    }

    private fun decode(bytes: ByteArray, origin: String): BookModule? = try {
        json.decodeFromString(BookModule.serializer(), bytes.decodeToString())
    } catch (e: Exception) {
        // A corrupt module must not take the reader down with it; the verse
        // list simply comes back empty and the UI says so.
        null
    }

    fun has(translation: String, book: String): Boolean = load(translation, book) != null

    /** Which translations actually have this book on this device, in order. */
    fun availableFor(book: String): List<Translation> =
        Translations.all.filter { has(it.id, book) }

    /** Write a module to local storage - used by import and by API caching. */
    fun save(translation: String, book: String, module: BookModule) {
        val file = localFile(translation, book)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(BookModule.serializer(), module))
        cache["$translation/$book"] = module
    }

    fun clearCache() = cache.clear()

    private companion object {
        const val MODULES_DIR = "modules"
        const val ASSETS_DIR = "bible"
    }
}
