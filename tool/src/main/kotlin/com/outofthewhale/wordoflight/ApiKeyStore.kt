package com.outofthewhale.wordoflight

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The reader's own API keys, sealed by [KeyCipher].
 *
 * One key per provider, because the three do not share credentials: Crossway
 * issues a key for the ESV, Tyndale one for the NLT, and a single API.Bible
 * key covers CSB, NKJV and AMP together.
 *
 * Nothing here ever returns a key to the UI. Settings asks only whether a
 * provider *has* one; the value goes straight from storage into a request
 * header, so there is no screen a shoulder-surfer could read it off.
 */
class ApiKeyStore(private val dataStore: DataStore<Preferences>) {

    /** Which providers currently hold a usable key. */
    val configured: Flow<Set<ApiProvider>> = dataStore.data.map { preferences ->
        ApiProvider.entries.filter { provider ->
            preferences[keyFor(provider)]?.let { KeyCipher.decrypt(it) }?.isNotBlank() == true
        }.toSet()
    }

    /** The translations that can actually be fetched with what is stored. */
    val readableTranslations: Flow<Set<String>> = configured.map { providers ->
        Translations.all
            .filter { it.provider == null || it.provider in providers }
            .map { it.id }
            .toSet()
    }

    suspend fun key(provider: ApiProvider): String? {
        val stored = dataStore.data.first()[keyFor(provider)] ?: return null
        return KeyCipher.decrypt(stored)?.takeIf { it.isNotBlank() }
    }

    suspend fun setKey(provider: ApiProvider, key: String) {
        val trimmed = key.trim()
        dataStore.edit { preferences ->
            if (trimmed.isEmpty()) {
                preferences.remove(keyFor(provider))
            } else {
                preferences[keyFor(provider)] = KeyCipher.encrypt(trimmed)
            }
        }
    }

    suspend fun clear(provider: ApiProvider) = setKey(provider, "")

    private fun keyFor(provider: ApiProvider) =
        stringPreferencesKey("apikey.${provider.name.lowercase()}")
}
