package dev.infusion.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

object InfusionTvField {

    val Container: Color = InfusionTvAppearance.Panel

    val Text: Color = InfusionTvAppearance.TextPrimary

    val FocusedText: Color = InfusionTvContour.FocusedContentColor

    private const val LABEL_ALPHA = 0.9f

    private const val PLACEHOLDER_ALPHA = 0.6f

    private const val FOCUSED_PLACEHOLDER_ALPHA = 0.7f

    private const val FOCUSED_SELECTION_ALPHA = 0.55f

    private const val SELECTION_ALPHA = 0.40f

    val Label: Color = Text.copy(alpha = LABEL_ALPHA)

    val Placeholder: Color = Text.copy(alpha = PLACEHOLDER_ALPHA)

    val DisabledContent: Color = Text.copy(alpha = InfusionTvContour.DisabledContentAlpha)

    val UnfocusedBorder: Color = InfusionTvAppearance.PanelBorder

    val FocusBorderWidth: Dp = 1.dp

    val ContentPadding: PaddingValues = PaddingValues(12.dp)

    val Shape: Shape = InfusionTvContour.Shape

    val Error: Color = InfusionTvAppearance.ErrorText

    fun labelColor(): Color = Label

    fun placeholderColor(focused: Boolean = false): Color = if (focused) {
        FocusedText.copy(alpha = FOCUSED_PLACEHOLDER_ALPHA)
    } else {
        Placeholder
    }

    fun textColor(enabled: Boolean = true, focused: Boolean = false): Color = when {
        !enabled -> DisabledContent
        focused -> FocusedText
        else -> Text
    }

    fun containerColor(focused: Boolean, enabled: Boolean = true, accent: Color): Color =
        if (focused && enabled) accent else Container

    fun cursorColor(focused: Boolean, enabled: Boolean = true, accent: Color): Color =
        if (focused && enabled) FocusedText else accent
}

@Composable
fun infusionTvFieldTextStyle(
    style: TextStyle = InfusionTvScaleCalibration.Type.Body,
    enabled: Boolean = true,
    focused: Boolean = false,
): TextStyle = style.copy(color = InfusionTvField.textColor(enabled, focused))

@Composable
fun infusionTvFieldCursorBrush(
    accent: Color = LocalInfusionAccent.current,
    focused: Boolean = false,
): Brush = SolidColor(InfusionTvField.cursorColor(focused, accent = accent))

@Composable
fun infusionTvFieldSelectionColors(
    focused: Boolean,
    enabled: Boolean = true,
    accent: Color = LocalInfusionAccent.current,
): TextSelectionColors = if (focused && enabled) {
    TextSelectionColors(
        handleColor = InfusionTvField.FocusedText,
        backgroundColor = InfusionTvAppearance.TextPrimary.copy(alpha = 0.55f),
    )
} else {
    TextSelectionColors(
        handleColor = accent,
        backgroundColor = accent.copy(alpha = 0.40f),
    )
}

@Composable
fun infusionTvFieldSurface(
    focused: Boolean,
    enabled: Boolean = true,
    accent: Color = LocalInfusionAccent.current,
    shape: Shape = InfusionTvField.Shape,
): Modifier = Modifier
    .background(InfusionTvField.containerColor(focused, enabled, accent), shape)
    .border(
        width = InfusionTvField.FocusBorderWidth,
        color = infusionTvFieldBorderColor(focused = focused, enabled = enabled, accent = accent),
        shape = shape,
    )

@Composable
fun infusionTvFieldBorderColor(
    focused: Boolean,
    enabled: Boolean = true,
    accent: Color = LocalInfusionAccent.current,
): Color = if (focused && enabled) accent else InfusionTvField.UnfocusedBorder

@Composable
fun InfusionTvFieldLabel(
    text: String,
    style: TextStyle = InfusionTvScaleCalibration.Type.Emphasis,
) {
    Text(text, style = style, color = InfusionTvField.labelColor())
}

@Composable
fun InfusionTvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secret: Boolean = false,
    placeholder: String? = null,
    textStyle: TextStyle = InfusionTvScaleCalibration.Type.Body,
) {
    var focused by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalTextSelectionColors provides infusionTvFieldSelectionColors(focused, enabled),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier
                .then(infusionTvFieldSurface(focused, enabled))
                .padding(InfusionTvField.ContentPadding)
                .onFocusChanged { focused = it.isFocused }
                .then(modifier),
            textStyle = infusionTvFieldTextStyle(textStyle, enabled, focused),
            cursorBrush = infusionTvFieldCursorBrush(focused = focused),
            singleLine = true,
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            decorationBox = { innerTextField ->
                Box {
                    if (placeholder != null && value.isEmpty()) {
                        Text(
                            placeholder,
                            style = textStyle,
                            color = InfusionTvField.placeholderColor(focused),
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
fun InfusionTvStateTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secret: Boolean = false,
    textStyle: TextStyle = InfusionTvScaleCalibration.Type.Body,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val sharedModifier = Modifier
        .then(infusionTvFieldSurface(focused, enabled))
        .padding(InfusionTvField.ContentPadding)
        .onFocusChanged { focused = it.isFocused }
        .then(modifier)
    val resolvedStyle = infusionTvFieldTextStyle(textStyle, enabled, focused)
    val cursorBrush = infusionTvFieldCursorBrush(focused = focused)
    CompositionLocalProvider(
        LocalTextSelectionColors provides infusionTvFieldSelectionColors(focused, enabled),
    ) {
        if (secret) {
            BasicSecureTextField(
                state = state,
                enabled = enabled,
                textStyle = resolvedStyle,
                cursorBrush = cursorBrush,
                keyboardOptions = keyboardOptions,
                onKeyboardAction = onKeyboardAction,
                modifier = sharedModifier,
            )
        } else {
            BasicTextField(
                state = state,
                enabled = enabled,
                textStyle = resolvedStyle,
                lineLimits = TextFieldLineLimits.SingleLine,
                cursorBrush = cursorBrush,
                keyboardOptions = keyboardOptions,
                onKeyboardAction = onKeyboardAction,
                modifier = sharedModifier,
            )
        }
    }
}
