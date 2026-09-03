package com.outofthewhale.wordoflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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

/** Pick a chapter within one book. Returns the chapter number. */
class ChapterSelectScreen(
    sealedActivity: SealedLightActivity,
    private val book: Book,
) : SimpleLightScreen<Int>(sealedActivity) {

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
                    text = book.name,
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 20.dp),
                )

                // Laid out by hand in rows: the SDK exposes no grid primitive,
                // and Psalms needs 150 cells to stay tappable on this screen.
                (1..book.chapters).chunked(COLUMNS).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEach { chapter ->
                            LightText(
                                text = chapter.toString(),
                                variant = LightTextVariant.Copy,
                                align = TextAlign.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .lightClickable { goBack(chapter) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                        // Keep the last row's cells the same width as the rest.
                        repeat(COLUMNS - row.size) {
                            LightText(
                                text = "",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

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

    private companion object {
        const val COLUMNS = 5
    }
}
