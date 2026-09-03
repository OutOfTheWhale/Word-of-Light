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

class CompareViewModel(
    private val repository: ChapterRepository,
    private val verses: List<VerseRef>,
    private val readable: Set<String>,
) : LightViewModel<Unit>() {

    data class Row(
        val translation: Translation,
        val text: String? = null,
        val status: String? = null,
    )

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows

    val label: String = describe(verses)

    init {
        val wanted = Translations.all.filter { it.id in readable }
        _rows.value = wanted.map { Row(it, status = "…") }

        viewModelScope.launch {
            // One at a time rather than all at once: each miss is a request
            // against a monthly allowance, and the first row is readable while
            // the rest arrive.
            wanted.forEachIndexed { index, translation ->
                val row = load(translation)
                _rows.value = _rows.value.toMutableList().also { it[index] = row }
            }
        }
    }

    private suspend fun load(translation: Translation): Row {
        val ref = verses.firstOrNull()?.chapterRef()
            ?: return Row(translation, status = "Nothing selected")

        return when (val result = repository.chapter(translation, ref)) {
            is Fetched.Ok -> {
                val wanted = verses.map { it.verse }.toSet()
                val text = result.verses
                    .filter { it.verse in wanted }
                    .joinToString(" ") { it.render() }
                if (text.isBlank()) {
                    Row(translation, status = "Not in this translation")
                } else {
                    Row(translation, text = text)
                }
            }

            Fetched.NoKey -> Row(translation, status = "No key saved")
            Fetched.NotEntitled -> Row(translation, status = "This key cannot read it")
            Fetched.QuotaExceeded -> Row(translation, status = "Request limit reached")
            is Fetched.Failed -> Row(translation, status = result.reason)
        }
    }

    private companion object {
        /** "Genesis 1:1", or "Genesis 1:1-3" for a run. */
        fun describe(verses: List<VerseRef>): String {
            val first = verses.firstOrNull() ?: return "Compare"
            if (verses.size == 1) return first.label()
            val last = verses.last()
            return "${first.label()}-${last.verse}"
        }
    }
}

/**
 * One passage, read in every translation available on this device.
 *
 * The point of the app's translation list, and only useful once more than one
 * of them can be read - which is why it arrived after the fetch layer rather
 * than with the rest of the selection actions.
 */
class CompareScreen(
    sealedActivity: SealedLightActivity,
    private val repository: ChapterRepository,
    private val verses: List<VerseRef>,
    private val readable: Set<String>,
) : LightScreen<Unit, CompareViewModel>(sealedActivity) {

    override val viewModelClass: Class<CompareViewModel>
        get() = CompareViewModel::class.java

    override fun createViewModel() = CompareViewModel(repository, verses, readable)

    @Composable
    override fun Content() {
        val rows by viewModel.rows.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            LightScrollView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background)
                    .padding(horizontal = 24.dp, vertical = 32.dp)
            ) {
                LightText(
                    text = viewModel.label,
                    variant = LightTextVariant.Heading,
                    modifier = Modifier.padding(bottom = 20.dp),
                )

                if (rows.size <= 1) {
                    LightText(
                        text = "Only one translation is available. Add an API key in " +
                            "Settings to compare.",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )
                }

                rows.forEach { row ->
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                        LightText(
                            text = row.translation.abbreviation,
                            variant = LightTextVariant.Fine,
                            lighten = true,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        LightText(
                            text = row.text ?: row.status.orEmpty(),
                            variant = LightTextVariant.Paragraph,
                            lighten = row.text == null,
                        )
                    }
                }

                LightText(
                    text = "BACK",
                    variant = LightTextVariant.Button,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .lightClickable { goBack() },
                )
            }
        }
    }
}
