package com.outofthewhale.wordoflight

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A translation as API.Bible reports it, once a key can see it. */
@Serializable
data class RemoteBible(
    val id: String = "",
    val abbreviation: String = "",
    val abbreviationLocal: String = "",
    val name: String = "",
) {
    /** The label to match against our own [Translation.abbreviation]. */
    val shortName: String get() = abbreviation.ifBlank { abbreviationLocal }
}

@Serializable
private data class BiblesEnvelope(val data: List<RemoteBible> = emptyList())

@Serializable
private data class ChapterEnvelope(val data: ChapterPayload? = null)

@Serializable
private data class ChapterPayload(
    val id: String = "",
    val content: String = "",
    val reference: String = "",
    val verseCount: Int = 0,
    val copyright: String = "",
)

/**
 * What came back, said plainly enough for the reader to show.
 *
 * A failure here is not exceptional - keys expire, quotas run out, phones lose
 * signal - so it is a value to display rather than something to throw.
 */
sealed interface Fetched {
    data class Ok(val verses: List<Verse>, val copyright: String) : Fetched
    data object NoKey : Fetched
    data object NotEntitled : Fetched
    data object QuotaExceeded : Fetched
    data class Failed(val reason: String) : Fetched
}

/**
 * Reads chapters from API.Bible using the reader's own key.
 *
 * Text is requested as plain text with verse numbers and parsed by
 * [ChapterParser], rather than as the structured JSON form. Plain text is a
 * shape we already handle and have tests for; the JSON form is a second schema
 * to track, and its structure is not worth depending on for a chapter of prose.
 *
 * Base URL note: American Bible Society replaced `scripture.api.bible` during
 * 2026. Anything still pointing at the old host will fail to resolve.
 */
class BibleApi(private val keyStore: ApiKeyStore) {

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    /**
     * Which translations this key can actually read.
     *
     * Called when a key is saved: it validates the key immediately, and the
     * ids that come back are the ones this account was granted. Hardcoding ids
     * for CSB, NKJV and AMP would assume a particular set of selections on a
     * particular plan.
     */
    suspend fun bibles(): Result<List<RemoteBible>> {
        val key = keyStore.key(ApiProvider.API_BIBLE)
            ?: return Result.failure(IllegalStateException("No API.Bible key saved"))
        return try {
            val response = client.get("$BASE/bibles") {
                header(KEY_HEADER, key)
                parameter("language", "eng")
            }
            if (!response.status.isSuccess()) {
                Result.failure(IllegalStateException(describe(response.status)))
            } else {
                Result.success(response.body<BiblesEnvelope>().data)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** One chapter. [book] is a canon id such as "gen"; ids are USFM already. */
    suspend fun chapter(bibleId: String, book: String, chapter: Int): Fetched {
        val key = keyStore.key(ApiProvider.API_BIBLE) ?: return Fetched.NoKey
        return try {
            val response = client.get("$BASE/bibles/$bibleId/chapters/${chapterId(book, chapter)}") {
                header(KEY_HEADER, key)
                parameter("content-type", "text")
                parameter("include-verse-numbers", true)
                parameter("include-titles", false)
                parameter("include-chapter-numbers", false)
                parameter("include-notes", false)
            }
            when {
                response.status == HttpStatusCode.Unauthorized ||
                    response.status == HttpStatusCode.Forbidden -> Fetched.NotEntitled
                response.status == HttpStatusCode.TooManyRequests -> Fetched.QuotaExceeded
                !response.status.isSuccess() -> Fetched.Failed(describe(response.status))
                else -> {
                    val payload = response.body<ChapterEnvelope>().data
                        ?: return Fetched.Failed("Empty response")
                    val verses = ChapterParser.parse(payload.content, chapter)
                    if (verses.isEmpty()) {
                        Fetched.Failed("Nothing could be read from the response")
                    } else {
                        Fetched.Ok(verses, payload.copyright)
                    }
                }
            }
        } catch (e: Exception) {
            Fetched.Failed(e.message ?: "Request failed")
        }
    }

    fun close() = client.close()

    /** Our canon ids are already USFM, so this is only a case change. */
    private fun chapterId(book: String, chapter: Int) = "${book.uppercase()}.$chapter"

    private fun describe(status: HttpStatusCode) = when (status.value) {
        401, 403 -> "This key is not permitted to read that translation"
        404 -> "Not found for this translation"
        429 -> "Monthly request limit reached"
        in 500..599 -> "The publisher's service is unavailable"
        else -> "Request failed (${status.value})"
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    private companion object {
        const val BASE = "https://rest.api.bible/v1"
        const val KEY_HEADER = "api-key"
    }
}
