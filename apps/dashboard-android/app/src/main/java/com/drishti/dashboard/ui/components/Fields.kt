package com.drishti.dashboard.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.drishti.dashboard.ui.theme.InkMuted
import com.drishti.dashboard.ui.theme.InkStrong
import com.drishti.dashboard.ui.theme.Paper
import com.drishti.dashboard.ui.theme.Tone

/**
 * A text box sized for a desk, not a form.
 *
 * 68dp tall with 18sp text: tappable without aiming, and readable by someone
 * typing a name into it while looking somewhere else. Everything the operator
 * types on this dashboard goes through here so the three fields cannot drift
 * apart.
 */
@Composable
fun BigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    imeAction: ImeAction = ImeAction.Done,
    singleLine: Boolean = true,
) {
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleMedium) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp),
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.titleMedium,
            shape = MaterialTheme.shapes.medium,
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                )
            },
            placeholder = placeholder?.let {
                {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = InkMuted,
                    )
                }
            },
            leadingIcon = leadingIcon?.let {
                {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = tone.ink,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Paper,
                unfocusedContainerColor = Paper,
                focusedBorderColor = tone.strong,
                unfocusedBorderColor = tone.edge,
                focusedLabelColor = tone.ink,
                unfocusedLabelColor = InkMuted,
                focusedTextColor = InkStrong,
                unfocusedTextColor = InkStrong,
                cursorColor = tone.strong,
            ),
        )
    }
}
