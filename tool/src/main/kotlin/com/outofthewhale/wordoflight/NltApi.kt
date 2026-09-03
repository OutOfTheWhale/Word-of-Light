package com.outofthewhale.wordoflight

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode

/**
 * Reads the New Living Translation from Tyndale.
 *
 * Deliberately separate from [BibleApi]: Tyndale's API agrees with API.Bible on
 * nothing that matters. The key travels as a query parameter rather than a
 * header, references read `Genesis.1` rather than `GEN.1`, and the response is
 * a whole HTML document rather than text or JSON. One client serving both would
 * be a tangle of conditionals for no gain.
 *
 * Parsing lives in [NltHtml] so it can be tested without a network or a key.
 */
class NltApi(private val keyStore: ApiKeyStore) {

    private val client by lazy { HttpClient(OkHttp) }

    suspend fun chapter(book: String, chapter: Int): Fetched {
        val key = keyStore.key(ApiProvider.NLT_API) ?: return Fetched.NoKey
        val reference = reference(book) ?: return Fetched.Failed("Unknown book")

        return try {
            val response = client.get(ENDPOINT) {
                parameter("ref", "$reference.$chapter")
                parameter("version", "NLT")
                parameter("key", key)
            }
            when {
                response.status == HttpStatusCode.Unauthorized ||
                    response.status == HttpStatusCode.Forbidden -> Fetched.NotEntitled
                response.status == HttpStatusCode.TooManyRequests -> Fetched.QuotaExceeded
                response.status.value !in 200..299 ->
                    Fetched.Failed("Request failed (${response.status.value})")
                else -> {
                    val verses = NltHtml.parse(response.bodyAsText(), chapter)
                    if (verses.isEmpty()) {
                        Fetched.Failed("Nothing could be read from the response")
                    } else {
                        Fetched.Ok(verses, COPYRIGHT)
                    }
                }
            }
        } catch (e: Exception) {
            Fetched.Failed(e.message ?: "Request failed")
        }
    }

    fun close() = client.close()

    /**
     * Tyndale names books in full without spaces - "Genesis", "1Samuel".
     * Derived from the canon rather than kept as a second table that could
     * drift out of step with it.
     */
    private fun reference(book: String): String? =
        Canon.book(book)?.name?.replace(" ", "")

    private companion object {
        const val ENDPOINT = "https://api.nlt.to/api/passages"
        const val COPYRIGHT = "New Living Translation, Tyndale House Publishers"
    }
}
