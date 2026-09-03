package com.outofthewhale.wordoflight

/**
 * One way to get a chapter, whatever translation it is in.
 *
 * The reader and the compare screen both need this, and neither should care
 * that the KJV comes off the disk while the rest arrive over the network from
 * two publishers whose APIs have nothing in common.
 *
 * Order is always: bundled or sideloaded module, then the local cache, then the
 * network. A chapter fetched once never costs a request again.
 */
class ChapterRepository(
    private val store: ModuleStore,
    private val cache: ChapterCache,
    private val keyStore: ApiKeyStore,
    private val bibleApi: BibleApi,
    private val nltApi: NltApi,
) {

    /** Verses already on the device, or null. Never touches the network. */
    fun local(translation: Translation, ref: ChapterRef): List<Verse>? =
        if (translation.source != Source.API) {
            store.load(translation.id, ref.book)?.chapter(ref.chapter)?.takeIf { it.isNotEmpty() }
        } else {
            cache.get(translation.id, ref)
        }

    /** Local if we have it, otherwise fetched and then kept. */
    suspend fun chapter(translation: Translation, ref: ChapterRef): Fetched {
        local(translation, ref)?.let { return Fetched.Ok(it, "") }

        val result = when (translation.provider) {
            ApiProvider.API_BIBLE -> {
                val bibleId = keyStore.bibleId(translation.id)
                    ?: return Fetched.Failed(
                        "${translation.abbreviation} is not set up yet. Open Settings to finish."
                    )
                bibleApi.chapter(bibleId, ref.book, ref.chapter)
            }

            ApiProvider.NLT_API -> nltApi.chapter(ref.book, ref.chapter)

            else -> return Fetched.Failed("${translation.abbreviation} is not supported yet.")
        }

        if (result is Fetched.Ok) cache.put(translation.id, ref, result.verses)
        return result
    }

    fun close() {
        bibleApi.close()
        nltApi.close()
    }
}
