package com.outofthewhale.wordoflight

import androidx.compose.foundation.background
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Write a note against a verse, a selection, or a whole chapter.
 *
 * Returns the text, or null when dismissed. Submitting an empty note is how a
 * note gets deleted - [Marks] drops a record once nothing is left on it.
 */
class NoteEditScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initialValue: String,
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val textState = rememberTextFieldState(initialValue)
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            LightTextInputEditor(
                title = title,
                state = textState,
                keyboardOptionsFlow = keyboardOptionsFlow,
                onSubmit = { result -> goBack(result.toString()) },
                onBack = { goBack(null) },
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = "SAVE",
                initialCaps = true,
            )
        }
    }
}
