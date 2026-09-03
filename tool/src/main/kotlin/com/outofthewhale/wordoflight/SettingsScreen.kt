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

    init {
        viewModelScope.launch {
            keyStore.configured.collect { _configured.value = it }
        }
    }

    fun setKey(provider: ApiProvider, key: String) {
        viewModelScope.launch { keyStore.setKey(provider, key) }
    }

    fun clear(provider: ApiProvider) {
        viewModelScope.launch { keyStore.clear(provider) }
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
