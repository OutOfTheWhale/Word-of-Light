package com.outofthewhale.wordoflight.lp2

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.datastore.preferences.preferencesDataStore
import com.outofthewhale.wordoflight.ApiKeyStore
import com.outofthewhale.wordoflight.BibleApi
import com.outofthewhale.wordoflight.ChapterCache
import com.outofthewhale.wordoflight.ChapterRepository
import com.outofthewhale.wordoflight.MarksStore
import com.outofthewhale.wordoflight.ModuleStore
import com.outofthewhale.wordoflight.NltApi

private val Context.dataStore by preferencesDataStore(name = "wordoflight")

/**
 * The Light Phone 2 build.
 *
 * Everything below the screen layer is the same code the LP3 tool runs - the
 * canon, the module store, the Strong's lexicon and concordance, the mark
 * storage, the encrypted keys and both publisher clients are shared by source
 * path, not copied. Only the drawing differs, because `LightScreen` and the
 * `Light*` primitives exist solely inside the SDK.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val keyStore = ApiKeyStore(dataStore)
        val repository = ChapterRepository(
            store = ModuleStore(filesDir) { path -> assets.open(path).use { it.readBytes() } },
            cache = ChapterCache(filesDir),
            keyStore = keyStore,
            bibleApi = BibleApi(keyStore),
            nltApi = NltApi(keyStore),
        )

        setContent {
            WordOfLightTheme {
                WordOfLightApp(
                    repository = repository,
                    marksStore = MarksStore(dataStore),
                    keyStore = keyStore,
                )
            }
        }
    }
}
