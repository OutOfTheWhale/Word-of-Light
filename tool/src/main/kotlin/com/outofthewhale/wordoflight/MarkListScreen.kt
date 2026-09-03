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

/** Which saved things a [MarkListScreen] is showing. */
enum class MarkList(val title: String, val empty: String) {
    RECENTS(
        title = "Recents",
        empty = "Chapters you open are listed here, most recent first.",
    ),
    BOOKMARKS(
        title = "Bookmarks",
        empty = "Flag a chapter from the header, or select a verse and choose MARK.",
    ),
    NOTES(
        title = "Notes",
        empty = "Select a verse while reading and choose NOTE.",
    ),
    HIGHLIGHTS(
        title = "Highlights",
        empty = "Select a verse while reading and choose UNDERLINE.",
    ),
}

/**
 * One screen for four lists, because they are the same thing: somewhere you
 * have been, or something you marked, and a way back to it.
 *
 * Returns the [ChapterRef] tapped so the reader can jump there.
 */
class MarkListScreen(
    sealedActivity: SealedLightActivity,
    private val list: MarkList,
    private val marksFlow: StateFlow<Marks>,
) : SimpleLightScreen<ChapterRef>(sealedActivity) {

    private data class Entry(
        val ref: ChapterRef,
        val label: String,
        val detail: String? = null,
    )

    @Composable
    override fun Content() {
        val marks by marksFlow.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val entries = entriesFor(marks)

        LightTheme(colors = themeColors) {
            LightScrollView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 24.dp, vertical = 24.dp)
            ) {
                LightText(
                    text = list.title,
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                if (entries.isEmpty()) {
                    LightText(
                        text = list.empty,
                        variant = LightTextVariant.Paragraph,
                        lighten = true,
                    )
                }

                entries.forEach { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { goBack(entry.ref) }
                            .padding(vertical = 10.dp)
                    ) {
                        LightText(text = entry.label, variant = LightTextVariant.Copy)
                        entry.detail?.let { detail ->
                            LightText(
                                text = detail,
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
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

    private fun entriesFor(marks: Marks): List<Entry> = when (list) {
        MarkList.RECENTS -> marks.recents.mapNotNull { recent ->
            recent.chapterRef()?.let { ref ->
                Entry(
                    ref = ref,
                    label = ref.label(),
                    detail = Translations.byId(recent.translation)?.abbreviation,
                )
            }
        }

        // Both kinds together: a flagged chapter and a marked verse are the
        // same gesture at different scales, and splitting them would mean
        // guessing which one the reader was looking for.
        MarkList.BOOKMARKS ->
            marks.bookmarkedChapters.map { Entry(it, it.label(), "chapter") } +
                marks.bookmarks.mapNotNull { mark ->
                    mark.verseRef()?.let { Entry(it.chapterRef(), it.label()) }
                }

        // noteEntries, not notes: one thought written across a passage is one
        // line here, not the same sentence once per verse.
        MarkList.NOTES ->
            marks.annotatedChapters.map { Entry(it, it.label(), marks.chapterNote(it)) } +
                marks.noteEntries.map { Entry(it.chapterRef(), it.label, it.note) }

        MarkList.HIGHLIGHTS -> marks.highlights.mapNotNull { mark ->
            mark.verseRef()?.let { Entry(it.chapterRef(), it.label()) }
        }
    }
}
