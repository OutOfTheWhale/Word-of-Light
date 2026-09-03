package com.outofthewhale.wordoflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * Pick a translation. Returns the id of the one chosen.
 *
 * Everything is listed, including translations that cannot be read yet, so the
 * list explains itself: an unavailable one says what it is waiting for rather
 * than silently not being there.
 */
class VersionSelectScreen(
    sealedActivity: SealedLightActivity,
    private val current: Translation,
    /** Why each translation cannot be read, by id; null means it can. */
    private val reasons: Map<String, String?>,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            LightScrollView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                LightText(
                    text = "Version",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 20.dp),
                )

                Translations.all.forEach { translation -> Row(translation) }

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
    private fun Row(translation: Translation) {
        val reason = reasons[translation.id]
        val ready = reason == null
        val selected = translation.id == current.id

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (ready) Modifier.lightClickable { goBack(translation.id) }
                    else Modifier
                )
                .padding(vertical = 10.dp)
        ) {
            LightText(
                text = if (selected) "${translation.abbreviation}  ·" else translation.abbreviation,
                variant = LightTextVariant.Copy,
                lighten = !ready,
            )
            LightText(
                text = if (ready) translation.name else "${translation.name} — $reason",
                variant = LightTextVariant.Fine,
                lighten = true,
            )
        }
    }
}
