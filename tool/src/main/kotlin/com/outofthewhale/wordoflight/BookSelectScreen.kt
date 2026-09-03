package com.outofthewhale.wordoflight

import androidx.compose.foundation.background
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
 * Pick a book, then a chapter.
 *
 * Chains straight into [ChapterSelectScreen] and returns the finished
 * [ChapterRef], so the reader gets one answer back rather than having to drive
 * a two-step flow itself.
 */
class BookSelectScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<ChapterRef>(sealedActivity) {

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
                    text = "Books",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 20.dp),
                )

                Section("OLD TESTAMENT")
                Canon.books.filter { it.isOldTestament }.forEach { BookRow(it) }

                Section("NEW TESTAMENT")
                Canon.books.filterNot { it.isOldTestament }.forEach { BookRow(it) }

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
    private fun Section(label: String) {
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
    }

    @Composable
    private fun BookRow(book: Book) {
        LightText(
            text = book.name,
            variant = LightTextVariant.Copy,
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable { openChapters(book) }
                .padding(vertical = 10.dp),
        )
    }

    private fun openChapters(book: Book) {
        // A one-chapter book has nothing to choose, so skip the second step.
        if (book.chapters == 1) {
            goBack(ChapterRef(book.id, 1))
            return
        }
        navigateTo({ activity -> ChapterSelectScreen(activity, book) }) { chapter ->
            goBack(ChapterRef(book.id, chapter))
        }
    }
}
