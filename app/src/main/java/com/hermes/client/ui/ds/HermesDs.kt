package com.hermes.client.ui.ds

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

data class HermesColors(
    val primary: Color = Color(0xFF4F7CFF),
    val onPrimary: Color = Color.White,
    val primaryContainer: Color = Color(0xFFDCE4FF),
    val onPrimaryContainer: Color = Color(0xFF0A1B4D),
    val accent: Color = Color(0xFF9B72CB),
    val surface: Color = Color(0xFFF6F7FB),
    val onSurface: Color = Color(0xFF14161D),
    val surfaceVariant: Color = Color(0xFFE7EAF3),
    val onSurfaceVariant: Color = Color(0xFF5B6070),
    val outline: Color = Color(0xFFD5D9E5),
    val error: Color = Color(0xFFB3261E),
    val errorContainer: Color = Color(0xFFF9DEDC),
    val onErrorContainer: Color = Color(0xFF410E0B),
    val userBubble: Color = Color(0xFFE4E9F7),
    val onUserBubble: Color = Color(0xFF101828),
    val scrim: Color = Color(0x99000000),
    val success: Color = Color(0xFF1B7A3D),
)

val HermesLightColors = HermesColors()

val HermesDarkColors = HermesColors(
    primary = Color(0xFF8FA8FF),
    onPrimary = Color(0xFF0A1330),
    primaryContainer = Color(0xFF2A3A6B),
    onPrimaryContainer = Color(0xFFDCE4FF),
    surface = Color(0xFF131419),
    onSurface = Color(0xFFE8EAF1),
    surfaceVariant = Color(0xFF23262F),
    onSurfaceVariant = Color(0xFFB9BED0),
    outline = Color(0xFF353A48),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF5A1512),
    onErrorContainer = Color(0xFFF9DEDC),
    userBubble = Color(0xFF2A3352),
    onUserBubble = Color(0xFFE8EAF1),
)

data class HermesType(
    val display: TextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp),
    val headline: TextStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    val title: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    val bodySmall: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    val label: TextStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    val labelSmall: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp),
    val mono: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
)

val LocalHermesColors = staticCompositionLocalOf { HermesLightColors }
val LocalHermesType = staticCompositionLocalOf { HermesType() }

object Hermes {
    val colors: HermesColors @Composable get() = LocalHermesColors.current
    val type: HermesType @Composable get() = LocalHermesType.current
}

@Composable
fun HermesDsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) HermesDarkColors else HermesLightColors
    CompositionLocalProvider(
        LocalHermesColors provides colors,
        LocalHermesType provides HermesType(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surface),
        ) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
fun HermesTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backDesc: String? = null,
    backTestTag: String = "topBack",
    navigation: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        navigation()
        if (onBack != null) {
            HermesIconButton(onClick = onBack, desc = backDesc, modifier = Modifier.testTag(backTestTag)) {
                HermesGlyph("‹", Hermes.type.title.copy(fontSize = 24.sp))
            }
        }
        BasicText(
            text = title,
            style = Hermes.type.title.copy(color = Hermes.colors.onSurface),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        )
        actions()
    }
}

/** Glyph text used instead of an icon font (‹ ✕ ＋ …). */
@Composable
fun HermesGlyph(text: String, style: TextStyle, color: Color = Hermes.colors.onSurface) {
    BasicText(text = text, style = style.copy(color = color))
}

@Composable
fun HermesScaffold(
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        topBar()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp),
            ) { snackbar() }
        }
        bottomBar()
    }
}

@Composable
fun HermesIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    desc: String? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Navigation drawer (no drag gesture; buttons drive it, like the tests do)
// ---------------------------------------------------------------------------

@Composable
fun HermesDrawer(
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
                    .background(Hermes.colors.scrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
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
                    .background(Hermes.colors.surface)
                    .padding(12.dp),
            ) {
                drawerContent()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------

@Composable
fun HermesButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val bg = if (primary) {
        Brush.horizontalGradient(listOf(Hermes.colors.primary, Hermes.colors.accent))
    } else {
        Brush.horizontalGradient(
            listOf(Hermes.colors.surfaceVariant, Hermes.colors.surfaceVariant)
        )
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 18.dp, vertical = 13.dp),
    ) {
        BasicText(
            text = label,
            style = Hermes.type.label.copy(
                color = if (primary) Hermes.colors.onPrimary else Hermes.colors.onSurface
            ),
        )
    }
}

@Composable
fun HermesTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        BasicText(
            text = label,
            style = Hermes.type.label.copy(color = Hermes.colors.primary),
        )
    }
}

// ---------------------------------------------------------------------------
// Text field
// ---------------------------------------------------------------------------

@Composable
fun HermesTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    secret: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    maxLines: Int = 1,
) {
    // The BasicTextField itself is the root so test tags and focus land on
    // the editable node (same contract as Material text fields).
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        cursorBrush = SolidColor(Hermes.colors.primary),
        textStyle = Hermes.type.body.copy(color = Hermes.colors.onSurface),
        modifier = modifier,
        decorationBox = { inner ->
            Column {
                if (label != null) {
                    BasicText(
                        text = label,
                        style = Hermes.type.labelSmall.copy(color = Hermes.colors.onSurfaceVariant),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Hermes.colors.surfaceVariant)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            BasicText(
                                text = placeholder,
                                style = Hermes.type.body.copy(
                                    color = Hermes.colors.onSurfaceVariant.copy(alpha = 0.7f)
                                ),
                            )
                        }
                        inner()
                    }
                    if (trailing != null) {
                        Box(Modifier.padding(start = 8.dp)) { trailing() }
                    }
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Card + dialog + snackbar + progress
// ---------------------------------------------------------------------------

@Composable
fun HermesCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Hermes.colors.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null) {
            BasicText(
                text = title,
                style = Hermes.type.title.copy(color = Hermes.colors.onSurface),
            )
        }
        content()
    }
}

@Composable
fun HermesDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    dismissOnOutside: Boolean = true,
    confirmLabel: String? = null,
    onConfirm: () -> Unit = {},
    dismissLabel: String? = null,
    onDismissAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    Dialog(onDismissRequest = { if (dismissOnOutside) onDismiss() else Unit }) {
        Column(
            modifier = modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Hermes.colors.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BasicText(
                text = title,
                style = Hermes.type.title.copy(color = Hermes.colors.onSurface),
            )
            content()
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (dismissLabel != null) {
                    HermesTextButton(dismissLabel, onDismissAction ?: onDismiss)
                    Spacer(Modifier.width(4.dp))
                }
                if (confirmLabel != null) {
                    HermesButton(confirmLabel, onConfirm)
                }
            }
        }
    }
}

class HermesSnackbarState {
    var message: String? by mutableStateOf(null)
        private set

    fun show(message: String) {
        this.message = message
    }

    fun dismiss() {
        message = null
    }
}

@Composable
fun rememberHermesSnackbarState(): HermesSnackbarState = remember { HermesSnackbarState() }

@Composable
fun HermesSnackbarHost(state: HermesSnackbarState) {
    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(3500)
            state.dismiss()
        }
    }
    val text = state.message
    if (text != null) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Hermes.colors.onSurface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            BasicText(
                text = text,
                style = Hermes.type.bodySmall.copy(color = Hermes.colors.surface),
            )
        }
    }
}

@Composable
fun HermesTypingDots(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        for (i in 0 until 3) {
            val scale by t.animateFloat(
                initialValue = 0.45f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(550, delayMillis = i * 140),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .alpha(0.45f + 0.55f * scale)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Hermes.colors.primary, Hermes.colors.accent)
                        )
                    ),
            )
        }
    }
}

@Composable
fun HermesLinearBar(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "bar")
    val slide by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Restart,
        ),
        label = "slide",
    )
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Hermes.colors.surfaceVariant),
    ) {
        val width = maxWidth * 0.35f
        val x = (maxWidth + width) * slide - width
        Box(
            modifier = Modifier
                .width(width)
                .offset(x = x)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Hermes.colors.primary, Hermes.colors.accent)
                    )
                ),
        )
    }
}

@Composable
fun HermesDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Hermes.colors.outline.copy(alpha = 0.6f)),
    )
}
