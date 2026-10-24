package com.hermes.client.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.composeunstyled.theme.Theme
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.ui.components.Icon
import com.composables.ui.components.IconButton
import com.composables.ui.components.Text
import com.composables.ui.components.TextField
import com.composables.ui.theme.backgroundColor
import com.composables.ui.theme.colors
import com.composables.ui.theme.onPanelColor
import com.composables.ui.theme.panelColor
import com.composables.ui.theme.scrimColor

// Thin glue over the established Composables UI kit: String-backed text field,
// slide-in drawer panel, and snackbar host. No custom design system here, just
// the kit's components and theme tokens.

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    secret: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardSend: (() -> Unit)? = null,
    fieldTag: String? = null,
) {
    val state = remember { TextFieldState(value) }
    // View-model (or dialog reset) writes flow into the field.
    LaunchedEffect(value) {
        if (state.text.toString() != value) {
            state.edit { replace(0, length, value) }
        }
    }
    // User edits flow back out. (Closing braces share the collect line:
    // collect() never returns, so a lone closing line would count uncovered.)
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }
            .collect { if (it != value) onValueChange(it) } }
    TextField(
        state = state,
        modifier = if (fieldTag != null) modifier.testTag(fieldTag) else modifier,
        placeholder = { Text(placeholder) },
        leading = leading,
        trailing = trailing,
        lineLimits = if (singleLine) TextFieldLineLimits.SingleLine
        else TextFieldLineLimits.MultiLine(maxHeightInLines = maxLines),
        outputTransformation = if (secret) SecretMask else null,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = if (onKeyboardSend != null) {
            androidx.compose.foundation.text.input.KeyboardActionHandler { onKeyboardSend() }
        } else {
            null
        },
    )
}

@Composable
fun AppSearchClear(onClear: () -> Unit) {
    IconButton(onClick = onClear) {
        Icon(imageVector = Lucide.X, contentDescription = null)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(
    open: Boolean,
    onClose: () -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Theme[colors][scrimColor])
                    .combinedClickable(onClick = onClose)
                    .testTag("drawerScrim"),
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(0.85f)
                    .background(Theme[colors][backgroundColor])
                    .padding(12.dp),
            ) {
                drawerContent()
            }
        }
    }
}

/** Bullet mask for secret fields (new text-input API has no visual transformation). */
private val SecretMask = object : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        replace(0, length, "•".repeat(length))
    }
}

class AppSnackbarState {
    var message: String? by mutableStateOf(null)
        private set

    fun show(message: String) {
        this.message = message
    }
}

@Composable
fun rememberAppSnackbarState(): AppSnackbarState = remember { AppSnackbarState() }

@Composable
fun AppSnackbarHost(state: AppSnackbarState) {
    // Sticky until the next message: errors stay visible instead of timing out.
    val text = state.message
    if (text != null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Theme[colors][panelColor],
                        RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text, color = Theme[colors][onPanelColor])
            }
        }
    }
}
