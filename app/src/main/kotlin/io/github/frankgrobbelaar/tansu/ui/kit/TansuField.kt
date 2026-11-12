package io.github.frankgrobbelaar.tansu.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.ui.theme.KiriCorners

/**
 * A labelled one-line field: the label above, a hint below where one helps. A [secret] field hides
 * what's typed, with a button to show it.
 */
@Composable
fun TansuField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    placeholder: String? = null,
    secret: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /** Text after what's typed that can't be changed, such as a file's extension. */
    suffix: String = "",
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle =
            MaterialTheme.typography.bodyLarge.copy(
                color = colors.onSurface,
                fontWeight = FontWeight.Medium,
            ),
        cursorBrush = SolidColor(colors.primary),
        visualTransformation =
            if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions =
            if (secret) {
                keyboardOptions.copy(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                )
            } else {
                keyboardOptions
            },
        interactionSource = interaction,
        // The label and hint sit inside the field, so TalkBack reads them with it.
        decorationBox = { field ->
            FieldFrame(label = label, hint = hint, focused = focused, roomForButton = secret) {
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    field()
                }
                if (suffix.isNotEmpty()) {
                    Text(
                        text = suffix,
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (secret) ShowButton(shown = shown, onClick = { shown = !shown })
            }
        },
    )
}

/** The label, the box the text sits in, and the hint. */
@Composable
private fun FieldFrame(
    label: String,
    hint: String?,
    focused: Boolean,
    roomForButton: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(48.dp)
                    .clip(KiriCorners.Field)
                    .background(colors.surfaceContainerLowest)
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) colors.primary else colors.outlineVariant,
                        shape = KiriCorners.Field,
                    )
                    .padding(start = 14.dp, end = if (roomForButton) 2.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShowButton(shown: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = if (shown) TansuIcons.EyeOff else TansuIcons.Eye,
            contentDescription =
                stringResource(if (shown) R.string.hide_password else R.string.show_password),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
