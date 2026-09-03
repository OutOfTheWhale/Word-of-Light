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
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything that is not reading.
 *
 * The reader itself keeps only what a reader needs - the text, the book and
 * chapter, and which translation. Anything else lives one tap behind the menu,
 * which is what lets the footer disappear entirely.
 *
 * Returns a [ChapterRef] when a destination is chosen anywhere below it, so
 * picking a bookmark three screens deep still lands the reader in the right
 * place.
 */
class MenuScreen(
    sealedActivity: SealedLightActivity,
    private val marksFlow: StateFlow<Marks>,
    private val keyStore: ApiKeyStore,
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
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                LightText(
                    text = "Menu",
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Item("Books", "Choose a book and chapter") { openBooks() }
                Item("Recents", count(marks.recents.size, "chapter")) {
                    openList(MarkList.RECENTS)
                }
                Item(
                    "Bookmarks",
                    count(marks.chapterBookmarks.size + marks.bookmarks.size, "saved"),
                ) { openList(MarkList.BOOKMARKS) }
                // noteEntries, not notes: the count has to agree with the list,
                // and a note written across a passage is one note there.
                Item("Notes", count(marks.chapterNotes.size + marks.noteEntries.size, "note")) {
                    openList(MarkList.NOTES)
                }
                Item("Highlights", count(marks.highlights.size, "verse")) {
                    openList(MarkList.HIGHLIGHTS)
                }
                Item("Settings", "Translations, keys and theme") { openSettings() }

                LightText(
                    text = "BACK",
                    variant = LightTextVariant.Button,
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .lightClickable { goBack() },
                )
            }
        }
    }

    @Composable
    private fun Item(label: String, detail: String, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .lightClickable(onClick = onClick)
                .padding(vertical = 12.dp)
        ) {
            LightText(text = label, variant = LightTextVariant.Subheading)
            LightText(text = detail, variant = LightTextVariant.Fine, lighten = true)
        }
    }

    private fun openBooks() {
        navigateTo(::BookSelectScreen) { destination -> goBack(destination) }
    }

    private fun openList(list: MarkList) {
        navigateTo({ activity -> MarkListScreen(activity, list, marksFlow) }) { destination ->
            goBack(destination)
        }
    }

    private fun openSettings() {
        navigateTo({ activity -> SettingsScreen(activity, keyStore) })
    }

    private companion object {
        /** "3 chapters", "1 note", or a prompt when there is nothing yet. */
        fun count(size: Int, noun: String): String = when (size) {
            0 -> "Nothing yet"
            1 -> "1 $noun"
            else -> "$size ${noun}s"
        }
    }
}
