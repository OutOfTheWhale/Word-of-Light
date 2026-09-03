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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the reader has saved, in canonical order.
 *
 * Returns a [ChapterRef] when one is tapped so the reader can jump to it.
 * Notes show their text; highlights and bookmarks show the reference alone,
 * because the verse text is one tap away and this list is for scanning.
 */
class MarksScreen(
    sealedActivity: SealedLightActivity,
    private val marksFlow: StateFlow<Marks>,
) : SimpleLightScreen<ChapterRef>(sealedActivity) {

    @Composable
    override fun Content() {
        val marks by marksFlow.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            LightScrollView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                LightText(
                    text = "Saved",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                val notes = marks.notes
                val highlights = marks.highlights
                val bookmarks = marks.bookmarks

                if (notes.isEmpty() && highlights.isEmpty() && bookmarks.isEmpty()) {
                    LightText(
                        text = "Nothing saved yet. Tap a verse while reading to " +
                            "highlight it, bookmark it, or add a note.",
                        variant = LightTextVariant.Paragraph,
                        lighten = true,
                    )
                }

                Section("NOTES", notes) { mark -> mark.note }
                Section("HIGHLIGHTS", highlights) { null }
                Section("BOOKMARKS", bookmarks) { null }

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
    private fun Section(
        label: String,
        marks: List<VerseMark>,
        detail: (VerseMark) -> String?,
    ) {
        if (marks.isEmpty()) return

        LightText(
            text = "$label  ${marks.size}",
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
        )

        marks.forEach { mark ->
            val ref = mark.verseRef() ?: return@forEach
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { goBack(ref.chapterRef()) }
                    .padding(vertical = 10.dp)
            ) {
                LightText(text = ref.label(), variant = LightTextVariant.Copy)
                detail(mark)?.takeIf { it.isNotBlank() }?.let { text ->
                    LightText(
                        text = text,
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
