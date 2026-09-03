package com.outofthewhale.wordoflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Holds the key writes.
 *
 * These deliberately do not run on a composition-scoped coroutine. Entering a
 * key navigates away to the keyboard editor, which disposes this screen's
 * composition; a `rememberCoroutineScope()` launch would then be cancelled
 * before the write landed, and silently - the key simply never arrived.
 */
class SettingsViewModel(private val keyStore: ApiKeyStore) : LightViewModel<Unit>() {

    private val _configured = MutableStateFlow<Set<ApiProvider>>(emptySet())
    val configured: StateFlow<Set<ApiProvider>> = _configured

    /** What discovery found, or why it could not. */
    private val _discovery = MutableStateFlow<String?>(null)
    val discovery: StateFlow<String?> = _discovery

    // Every state flow must be declared above this block. init runs in
    // declaration order, and viewModelScope launches on Dispatchers.Main
    // .immediate - so the coroutine body executes synchronously during
    // construction, before any property declared below here exists.
    init {
        viewModelScope.launch {
            keyStore.configured.collect { _configured.value = it }
        }
        viewModelScope.launch {
            // A key saved before discovery existed has no bindings, and so
            // unlocks nothing. Re-check on open rather than making the reader
            // re-enter a key they have already given us.
            val hasKey = keyStore.key(ApiProvider.API_BIBLE) != null
            val bound = keyStore.bibleIds.first().keys.any { id ->
                Translations.byId(id)?.provider == ApiProvider.API_BIBLE
            }
            if (hasKey && !bound) discover()
        }
    }

    fun setKey(provider: ApiProvider, key: String) {
        viewModelScope.launch {
            keyStore.setKey(provider, key)
            if (provider == ApiProvider.API_BIBLE && key.isNotBlank()) discover()
        }
    }

    fun clear(provider: ApiProvider) {
        viewModelScope.launch {
            keyStore.clear(provider)
            if (provider == ApiProvider.API_BIBLE) _discovery.value = null
        }
    }

    /**
     * Ask API.Bible what this key can read, and bind those ids.
     *
     * Doubles as validation: a bad key fails here, at the moment it is entered,
     * rather than silently later when a chapter will not load. The free tier
     * grants three translations chosen per account, so the ids cannot be
     * hardcoded.
     */
    private suspend fun discover() {
        _discovery.value = "Checking what this key can read…"
        val api = BibleApi(keyStore)
        try {
            api.bibles().fold(
                onSuccess = { remote ->
                    val wanted = Translations.all.filter { it.provider == ApiProvider.API_BIBLE }
                    val bindings = wanted.mapNotNull { translation ->
                        remote.firstOrNull {
                            it.shortName.equals(translation.abbreviation, ignoreCase = true)
                        }?.let { translation.id to it.id }
                    }.toMap()

                    keyStore.bindBibleIds(bindings)
                    _discovery.value = if (bindings.isEmpty()) {
                        "Key accepted, but it grants none of " +
                            wanted.joinToString(", ") { it.abbreviation } +
                            ". Check which translations your plan selected."
                    } else {
                        "Ready: " + bindings.keys
                            .mapNotNull { Translations.byId(it)?.abbreviation }
                            .joinToString(", ")
                    }
                },
                onFailure = { _discovery.value = it.message ?: "Could not reach API.Bible" },
            )
        } finally {
            api.close()
        }
    }
}

/**
 * Where the reader's own API keys go in.
 *
 * Three providers, because they do not share credentials. One API.Bible key
 * unlocks CSB, NKJV and AMP together; Crossway and Tyndale each issue their own
 * for the ESV and the NLT.
 *
 * A stored key is never displayed back - the screen shows only whether one is
 * present. Editing always starts from an empty field, so an existing key cannot
 * be read off the screen, only replaced.
 */
class SettingsScreen(
    sealedActivity: SealedLightActivity,
    private val keyStore: ApiKeyStore,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(keyStore)

    @Composable
    override fun Content() {
        val configured by viewModel.configured.collectAsState()
        val discovery by viewModel.discovery.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            LightScrollView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                LightText(
                    text = "Settings",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LightText(
                    text = "The KJV is built in and needs no key. Other " +
                        "translations are fetched from their publishers.",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 20.dp),
                )

                ApiProvider.entries.forEach { provider ->
                    ProviderRow(
                        provider = provider,
                        hasKey = provider in configured,
                        onEdit = {
                            navigateTo({ activity ->
                                // Always starts blank: a stored key is replaced,
                                // never shown.
                                NoteEditScreen(activity, "${provider.displayName} key", "")
                            }) { entered ->
                                viewModel.setKey(provider, entered)
                            }
                        },
                        onClear = { viewModel.clear(provider) },
                    )
                }

                discovery?.let { message ->
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }

                LightText(
                    text = "THEME",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
                )
                LightText(
                    text = if (LightThemeController.isDarkTheme) "Dark" else "Light",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable { LightThemeController.toggle() }
                        .padding(vertical = 8.dp),
                )

                LightText(
                    text = "BACK",
                    variant = LightTextVariant.Button,
                    modifier = Modifier
                        .padding(top = 28.dp)
                        .lightClickable { goBack() },
                )
            }
        }
    }

    @Composable
    private fun ProviderRow(
        provider: ApiProvider,
        hasKey: Boolean,
        onEdit: () -> Unit,
        onClear: () -> Unit,
    ) {
        val unlocks = Translations.all
            .filter { it.provider == provider }
            .joinToString(", ") { it.abbreviation }

        Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
            LightText(
                text = provider.displayName,
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = onEdit)
                    .padding(bottom = 2.dp),
            )
            LightText(
                text = unlocks,
                variant = LightTextVariant.Fine,
                lighten = true,
            )
            LightText(
                text = provider.keyUrl,
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                LightText(
                    text = if (hasKey) "KEY SAVED" else "NO KEY — TAP TO ADD",
                    variant = LightTextVariant.Fine,
                    lighten = !hasKey,
                    modifier = Modifier
                        .lightClickable(onClick = onEdit)
                        .padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
                )
                if (hasKey) {
                    LightText(
                        text = "REMOVE",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                        modifier = Modifier
                            .lightClickable(onClick = onClear)
                            .padding(top = 4.dp, bottom = 4.dp),
                    )
                }
            }
        }
    }
}
