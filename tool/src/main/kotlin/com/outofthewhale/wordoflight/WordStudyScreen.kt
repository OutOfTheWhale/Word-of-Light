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
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class WordStudyViewModel(
    private val lexicon: Lexicon,
    private val concordance: Concordance,
    val word: Word,
    val testament: Testament,
) : LightViewModel<ChapterRef>() {

    data class State(
        val entry: StrongsEntry? = null,
        val occurrences: List<VerseRef> = emptyList(),
        /** Set when the tag disagrees with the testament it was found in. */
        val inconsistent: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    override fun onScreenShow(screen: SimpleLightScreen<ChapterRef>) {
        super.onScreenShow(screen)
        val strong = word.strong ?: return

        // Refuse to present a Greek entry for an Old Testament word, or the
        // reverse. The importer checks this too, but a wrong answer here is
        // worse than none: it looks authoritative.
        if (!Tagging.isConsistent(strong, testament)) {
            _state.value = State(inconsistent = true)
            return
        }

        _state.value = State(
            entry = lexicon.lookup(strong),
            occurrences = concordance.occurrences(strong),
        )
    }

    val languageName: String
        get() = if (testament == Testament.OLD) "Hebrew" else "Greek"
}

/**
 * What a word is in the original language, and everywhere else it is used.
 *
 * Returns a [ChapterRef] when the reader taps one of the occurrences, so the
 * reader screen can jump there.
 */
class WordStudyScreen(
    sealedActivity: SealedLightActivity,
    private val word: Word,
    private val testament: Testament,
    private val lexicon: Lexicon,
    private val concordance: Concordance,
) : LightScreen<ChapterRef, WordStudyViewModel>(sealedActivity) {

    override val viewModelClass: Class<WordStudyViewModel>
        get() = WordStudyViewModel::class.java

    override fun createViewModel() =
        WordStudyViewModel(lexicon, concordance, word, testament)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            LightScrollView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                LightText(
                    text = word.bare,
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                word.strong?.let { strong ->
                    LightText(
                        text = "$strong  ·  ${viewModel.languageName}",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                }

                when {
                    word.strong == null -> Message(
                        "This word carries no Strong's number."
                    )

                    state.inconsistent -> Message(
                        "This word is tagged ${word.strong}, which does not " +
                            "belong to the ${viewModel.languageName} of this " +
                            "testament. Not showing a definition rather than " +
                            "showing a wrong one."
                    )

                    else -> Entry(state)
                }

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
    private fun Entry(state: WordStudyViewModel.State) {
        val entry = state.entry
        if (entry != null && !entry.isEmpty) {
            if (entry.word.isNotBlank()) {
                LightText(
                    text = entry.word,
                    variant = LightTextVariant.Subtitle,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            if (entry.translit.isNotBlank()) {
                LightText(
                    text = entry.translit,
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(bottom = 20.dp),
                )
            }

            // Usage leads, not definition. In this lexicon the definition
            // field splices the transliteration in front of a fragment
            // ("plural of", with nothing said of what) while usage carries the
            // actual senses. Both are shown, best one first.
            Field("MEANING", entry.usage)
            Field("STRONG'S", entry.definition)
            Field("ROOT", entry.root)
            Field("PART OF SPEECH", entry.pos)
        } else {
            // ~1% of tagged words, nearly all of them proper names.
            Message("No dictionary entry for this number. The occurrences below still apply.")
        }

        Occurrences(state.occurrences)
    }

    @Composable
    private fun Field(label: String, value: String) {
        if (value.isBlank()) return
        LightText(
            text = label,
            variant = LightTextVariant.Fine,
            lighten = true,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        LightText(
            text = value,
            variant = LightTextVariant.Paragraph,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }

    @Composable
    private fun Occurrences(occurrences: List<VerseRef>) {
        if (occurrences.isEmpty()) return

        val shown = occurrences.take(OCCURRENCE_LIMIT)
        LightText(
            text = "FOUND ${occurrences.size} VERSES",
            variant = LightTextVariant.Button,
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
        if (occurrences.size > shown.size) {
            LightText(
                text = "showing the first ${shown.size}",
                variant = LightTextVariant.Fine,
                lighten = true,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        shown.forEach { ref ->
            LightText(
                text = ref.label(),
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { goBack(ref.chapterRef()) }
                    .padding(vertical = 10.dp),
            )
        }
    }

    @Composable
    private fun Message(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Paragraph,
            lighten = true,
        )
    }

    private companion object {
        /**
         * Common words run to thousands of verses - G2532 ("and") appears in
         * 5,199. Rendering them all would stall the screen and be useless to
         * read, so the count is honest and the list is capped.
         */
        const val OCCURRENCE_LIMIT = 50
    }
}
