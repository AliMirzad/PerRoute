package app.perroute.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.perroute.model.ProxyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The colours of one theme. */
internal data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val foreground: Color,
    val muted: Color,
    val primary: Color,
    val primaryBright: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val isDark: Boolean
)

internal val DarkPalette = Palette(
    Color(0xFF080A12), Color(0xFF111522), Color(0xFF181D2D), Color(0xFFF5F7FF),
    Color(0xFF98A2B8), Color(0xFF806CFF), Color(0xFFAE7CFF), Color(0xFF5AE6D0),
    Color(0xFFFF6985), Color(0xFFFFC766), true
)
internal val LightPalette = Palette(
    Color(0xFFF3F5FA), Color(0xFFFFFFFF), Color(0xFFE9EDF5), Color(0xFF171A26),
    Color(0xFF5E687B), Color(0xFF5848D6), Color(0xFF6A50DF), Color(0xFF087B6B),
    Color(0xFFB72E4A), Color(0xFF865900), false
)
private val activePaletteState = mutableStateOf(DarkPalette)
internal var ActivePalette: Palette
    get() = activePaletteState.value
    set(value) { activePaletteState.value = value }
internal val Ink get() = ActivePalette.background
internal val Surface get() = ActivePalette.surface
internal val SurfaceHigh get() = ActivePalette.surfaceHigh
internal val White get() = ActivePalette.foreground
internal val Muted get() = ActivePalette.muted
internal val Violet get() = ActivePalette.primary
internal val Violet2 get() = ActivePalette.primaryBright
internal val Cyan get() = ActivePalette.success
internal val Red get() = ActivePalette.danger
internal val Amber get() = ActivePalette.warning
internal val NeutralOverlay get() = if (ActivePalette.isDark) Color.White else Color.Black
internal val GlassShape = RoundedCornerShape(22.dp)
internal val ControlShape = RoundedCornerShape(13.dp)
internal val RowShape = RoundedCornerShape(15.dp)
internal val UiFont = FontFamily(
    Font("fonts/Inter-Variable.ttf", FontWeight.Normal),
    Font("fonts/Inter-Variable.ttf", FontWeight.SemiBold),
    Font("fonts/Inter-Variable.ttf", FontWeight.Bold)
)

/** True after the keyboard was used to move around, so focus is only outlined for keyboard users. */
internal val KeyboardNavigation = mutableStateOf(false)

/** Open menus. While one is open, Escape belongs to that menu and must not reach the window. */
internal val OpenMenus = mutableStateOf(0)

internal fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .then(if (focused && KeyboardNavigation.value) Modifier.border(2.dp, Violet2, shape) else Modifier)
}

/** A clickable surface with a hand cursor, an accessible label and role, and a keyboard focus outline. */
internal fun Modifier.pressable(
    shape: Shape = ControlShape,
    enabled: Boolean = true,
    role: Role = Role.Button,
    label: String? = null,
    onClick: () -> Unit
): Modifier = this
    .focusRing(shape)
    .clip(shape)
    .clickable(enabled = enabled, onClickLabel = label, role = role, onClick = onClick)
    .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
    .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Tooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (text.isBlank()) { Box(modifier) { content() }; return }
    TooltipArea(
        tooltip = {
            Text(
                text, color = White, fontSize = 10.5.sp, lineHeight = 14.sp,
                modifier = Modifier.widthIn(max = 360.dp).shadow(8.dp, RoundedCornerShape(9.dp))
                    .clip(RoundedCornerShape(9.dp)).background(SurfaceHigh)
                    .border(1.dp, NeutralOverlay.copy(.12f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            )
        },
        modifier = modifier,
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 18.dp))
    ) { content() }
}

/** One line of text that shows its full value in a tooltip whenever it does not fit. */
@Composable
internal fun EllipsisText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight = FontWeight.Normal,
    fontFamily: FontFamily? = null
) {
    var clipped by remember(text) { mutableStateOf(false) }
    Tooltip(if (clipped) text else "", modifier) {
        Text(text, color = color, fontSize = fontSize, fontWeight = fontWeight, fontFamily = fontFamily, maxLines = 1,
            overflow = TextOverflow.Ellipsis, onTextLayout = { clipped = it.hasVisualOverflow })
    }
}

/** The translucent rounded surface used for panels and cards. */
@Composable
internal fun GlassCard(modifier: Modifier = Modifier, shape: Shape = GlassShape, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.clip(shape).background(if (ActivePalette.isDark) Color.White.copy(.055f) else Color.White.copy(.78f))
            .border(1.dp, if (ActivePalette.isDark) Color.White.copy(.09f) else Color.Black.copy(.08f), shape),
        content = content
    )
}

/** The main action of a screen or dialog. */
@Composable
internal fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier.height(42.dp).alpha(if (enabled) 1f else .45f)
            .pressable(ControlShape, enabled && !busy, label = label, onClick = onClick)
            .background(Brush.horizontalGradient(listOf(Violet, Color(0xFF9B65E9))))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A lower-emphasis action. */
@Composable
internal fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier.height(42.dp).alpha(if (enabled) 1f else .45f)
            .pressable(ControlShape, enabled, label = label, onClick = onClick)
            .background(NeutralOverlay.copy(.055f)).border(1.dp, NeutralOverlay.copy(.10f), ControlShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = White.copy(.88f), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Icon(trailing, null, tint = Muted, modifier = Modifier.size(15.dp))
        }
    }
}

/** An icon-only control. The label is its accessible name and its tooltip. */
@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Muted,
    enabled: Boolean = true,
    size: Dp = 32.dp,
    onClick: () -> Unit
) {
    Tooltip(label, modifier) {
        Box(
            Modifier.size(size).alpha(if (enabled) 1f else .4f)
                .pressable(RoundedCornerShape(10.dp), enabled, label = label, onClick = onClick)
                .background(NeutralOverlay.copy(.045f)).border(1.dp, NeutralOverlay.copy(.08f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * .52f)) }
    }
}

/** An on/off switch. The label is announced to assistive technology together with its state. */
@Composable
internal fun ToggleSwitch(checked: Boolean, label: String, enabled: Boolean = true, onToggle: (Boolean) -> Unit) {
    val track = if (checked) Violet else NeutralOverlay.copy(.16f)
    Box(
        Modifier.alpha(if (enabled) 1f else .45f).focusRing(CircleShape).clip(CircleShape)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .semantics { contentDescription = label; stateDescription = if (checked) "On" else "Off" }
            .size(width = 40.dp, height = 23.dp).background(track).border(1.dp, NeutralOverlay.copy(.10f), CircleShape)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) { Box(Modifier.size(17.dp).clip(CircleShape).background(if (checked) Color.White else Muted)) }
}

/** The visual of a check box; the surrounding row handles the toggling. */
@Composable
internal fun CheckMark(selected: Boolean) {
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) Violet else NeutralOverlay.copy(.05f))
            .border(1.dp, if (selected) Violet2 else NeutralOverlay.copy(.22f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
}

/** The visual of a radio button; the surrounding row handles the selection. */
@Composable
internal fun RadioMark(selected: Boolean) {
    Box(
        Modifier.size(18.dp).clip(CircleShape).border(1.5.dp, if (selected) Violet2 else NeutralOverlay.copy(.30f), CircleShape),
        contentAlignment = Alignment.Center
    ) { if (selected) Box(Modifier.size(9.dp).clip(CircleShape).background(Violet2)) }
}

/** A small labelled pill, optionally with an icon. */
@Composable
internal fun Badge(label: String, color: Color, filled: Boolean = false, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.clip(shape).background(if (filled) color else color.copy(.12f))
            .then(if (filled) Modifier else Modifier.border(1.dp, color.copy(.30f), shape))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (filled) Color.White else color, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = if (filled) Color.White else color, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** "Main" is a solid violet pill and "Custom" an outlined one, so the two never differ by colour alone. */
@Composable
internal fun RouteBadge(main: Boolean, long: Boolean = false) {
    if (main) Badge(if (long) "Main server" else "Main", Violet, filled = true)
    else Badge("Custom", Violet2)
}

/** A selectable chip used for filters and list sources. */
@Composable
internal fun FilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier.alpha(if (enabled) 1f else .45f).focusRing(shape).clip(shape)
            .selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .background(if (selected) Violet else NeutralOverlay.copy(.05f))
            .border(1.dp, if (selected) Violet2.copy(.55f) else NeutralOverlay.copy(.07f), shape)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Text(label, color = if (selected) Color.White else Muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A row of mutually exclusive choices. */
@Composable
internal fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
    enabled: Boolean = true,
    onSelect: (T) -> Unit
) {
    Row(
        modifier.clip(ControlShape).background(NeutralOverlay.copy(.045f)).border(1.dp, NeutralOverlay.copy(.08f), ControlShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val active = option == selected
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier.weight(1f).height(32.dp).alpha(if (enabled || active) 1f else .5f).focusRing(shape).clip(shape)
                    .selectable(active, enabled = enabled, role = Role.Tab) { onSelect(option) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .background(if (active) Violet else Color.Transparent).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                icon?.invoke(option)?.let {
                    Icon(it, null, tint = if (active) Color.White else Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label(option), color = if (active) Color.White else Muted, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A single-line text field. */
@Composable
internal fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    icon: ImageVector? = null,
    enabled: Boolean = true,
    monospace: Boolean = false
) {
    Row(
        modifier.height(42.dp).alpha(if (enabled) 1f else .5f).focusRing(ControlShape).clip(ControlShape)
            .background(NeutralOverlay.copy(.045f)).border(1.dp, NeutralOverlay.copy(.10f), ControlShape).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        BasicTextField(
            value, onValueChange, singleLine = true, enabled = enabled,
            textStyle = TextStyle(color = White, fontSize = 12.sp, fontFamily = if (monospace) FontFamily.Monospace else UiFont),
            cursorBrush = SolidColor(Violet2),
            modifier = Modifier.weight(1f).semantics { contentDescription = hint },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(hint, color = Muted.copy(.75f), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            }
        )
    }
}

/** A multi-line monospace text field. */
@Composable
internal fun TextArea(value: String, onValueChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier.fillMaxWidth().height(96.dp), enabled: Boolean = true) {
    BasicTextField(
        value, onValueChange, enabled = enabled,
        textStyle = TextStyle(color = White, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Violet2),
        modifier = modifier.alpha(if (enabled) 1f else .5f).focusRing(ControlShape).clip(ControlShape)
            .background(NeutralOverlay.copy(if (ActivePalette.isDark) .16f else .045f))
            .border(1.dp, NeutralOverlay.copy(.10f), ControlShape).padding(11.dp)
            .semantics { contentDescription = hint },
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Muted.copy(.7f), fontSize = 10.5.sp); inner() } }
    )
}

/** One line of a popup menu. */
internal class MenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val detail: String = "",
    val enabled: Boolean = true,
    val selected: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/** A themed dropdown menu with an optional header, for example a filter field. */
@Composable
internal fun PopupMenu(expanded: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>, minWidth: Dp = 190.dp, header: (@Composable () -> Unit)? = null) {
    DisposableEffect(expanded) {
        if (expanded) OpenMenus.value++
        onDispose { if (expanded) OpenMenus.value-- }
    }
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(min = minWidth, max = 380.dp).heightIn(max = 340.dp)
            .background(SurfaceHigh).border(1.dp, NeutralOverlay.copy(.10f), RoundedCornerShape(4.dp))
    ) {
        header?.invoke()
        entries.forEach { entry ->
            val color = when {
                entry.danger -> Red
                entry.selected -> Violet2
                else -> White
            }
            DropdownMenuItem(
                enabled = entry.enabled,
                onClick = { onDismiss(); entry.onClick() },
                leadingIcon = entry.icon?.let { icon -> { Icon(icon, null, tint = if (entry.enabled) color else Muted, modifier = Modifier.size(16.dp)) } },
                trailingIcon = if (entry.selected) ({ Icon(Icons.Rounded.Check, "Selected", tint = Violet2, modifier = Modifier.size(15.dp)) }) else null,
                text = {
                    Column {
                        Text(entry.label, color = if (entry.enabled) color else Muted, fontSize = 12.sp,
                            fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (entry.detail.isNotBlank()) Text(entry.detail, color = Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            )
        }
    }
}

/** A button that opens a menu and shows the current choice. */
@Composable
internal fun MenuButton(
    label: String,
    entries: List<MenuEntry>,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accessibleLabel: String = label,
    enabled: Boolean = true,
    height: Dp = 42.dp
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(height).alpha(if (enabled) 1f else .45f)
                .pressable(ControlShape, enabled, label = accessibleLabel) { open = true }
                .background(NeutralOverlay.copy(.055f)).border(1.dp, NeutralOverlay.copy(.10f), ControlShape)
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Muted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(7.dp))
            }
            Text(label, color = White.copy(.9f), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.UnfoldMore, null, tint = Muted, modifier = Modifier.size(15.dp))
        }
        PopupMenu(open, { open = false }, entries)
    }
}

/** An icon button that opens a menu, for overflow actions. */
@Composable
internal fun MenuIcon(icon: ImageVector, label: String, entries: List<MenuEntry>, modifier: Modifier = Modifier, size: Dp = 32.dp, tint: Color = Muted) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconAction(icon, label, tint = tint, size = size) { open = true }
        PopupMenu(open, { open = false }, entries)
    }
}

/** A one-line explanation or warning, optionally with an action on the right. */
@Composable
internal fun InfoBanner(text: String, modifier: Modifier = Modifier, color: Color = Muted, icon: ImageVector = Icons.Rounded.Info, action: (@Composable RowScope.() -> Unit)? = null) {
    val tinted = color != Muted
    Row(
        modifier.clip(RowShape).background(if (tinted) color.copy(.09f) else NeutralOverlay.copy(.04f))
            .border(1.dp, if (tinted) color.copy(.26f) else NeutralOverlay.copy(.07f), RowShape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(9.dp))
        Text(text, color = if (tinted) color else White.copy(.78f), fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
        if (action != null) { Spacer(Modifier.width(9.dp)); action() }
    }
}

/** What a list shows when it has nothing to show, with the next step to take. */
@Composable
internal fun EmptyState(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(Violet.copy(.13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Violet2, modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(max = 380.dp))
        if (action != null) { Spacer(Modifier.height(14.dp)); action() }
    }
}

/** A spinner with a short description of what is being loaded. */
@Composable
internal fun LoadingState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = Violet2, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(11.dp))
        Text(text, color = Muted, fontSize = 11.sp)
    }
}

/** A small coloured dot; always used next to text that says the same thing. */
@Composable
internal fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** An upper-case heading for a group of rows. */
@Composable
internal fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Muted, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
}

/** The label above a form field. */
@Composable
internal fun FieldLabel(text: String) {
    Text(text, color = Muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(5.dp))
}

/** The frame every screen shares: a fixed heading with actions, scrolling content and a fixed action bar. */
@Composable
internal fun ScreenPanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (actions != null) {
                    Spacer(Modifier.width(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).fillMaxWidth(), content = content)
            if (footer != null) {
                Spacer(Modifier.height(11.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically, content = footer)
            }
        }
    }
}

/** A modal card with a fixed title, scrolling body and fixed actions. It is shown through [Shell.showDialog]. */
@Composable
internal fun DialogCard(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    width: Dp = 520.dp,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        Modifier.padding(22.dp).widthIn(max = width).fillMaxWidth().shadow(30.dp, GlassShape).clip(GlassShape)
            .background(Surface).border(1.dp, NeutralOverlay.copy(.11f), GlassShape)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(subtitle, color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                IconAction(Icons.Rounded.Close, "Close", onClick = onClose)
            }
            Spacer(Modifier.height(13.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Asks for confirmation before an action that cannot be undone. */
@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: String, onCancel: () -> Unit, danger: Boolean = false, onConfirm: () -> Unit) {
    DialogCard(title, "", onCancel, width = 430.dp, actions = {
        SecondaryButton("Cancel", onClick = onCancel)
        if (danger) SecondaryButton(confirm, icon = Icons.Rounded.Close, onClick = onConfirm)
        else PrimaryButton(confirm, onClick = onConfirm)
    }) {
        Text(message, color = White.copy(.82f), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

private val appIconCache = ConcurrentHashMap<String, java.util.Optional<ImageBitmap>>()

/** The executable's own icon when Windows can provide one. Callers fall back to the letter avatar. */
@Composable
private fun rememberAppIcon(path: String): ImageBitmap? {
    val key = path.lowercase()
    val state = produceState(appIconCache[key]?.orElse(null), key) {
        if (appIconCache.containsKey(key)) { value = appIconCache[key]?.orElse(null); return@produceState }
        value = withContext(Dispatchers.IO) {
            val loaded = runCatching {
                val file = java.io.File(path)
                if (!file.isFile) return@runCatching null
                val icon = javax.swing.filechooser.FileSystemView.getFileSystemView().getSystemIcon(file, 32, 32) ?: return@runCatching null
                val image = java.awt.image.BufferedImage(icon.iconWidth.coerceAtLeast(1), icon.iconHeight.coerceAtLeast(1), java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try { icon.paintIcon(null, graphics, 0, 0) } finally { graphics.dispose() }
                image.toComposeImageBitmap()
            }.getOrNull()
            appIconCache[key] = java.util.Optional.ofNullable(loaded)
            loaded
        }
    }
    return state.value
}

/** The application icon, or a coloured initial when Windows has none for it. */
@Composable
internal fun AppAvatar(name: String, path: String = "", size: Dp = 34.dp) {
    val icon = if (path.isBlank()) null else rememberAppIcon(path)
    val shape = RoundedCornerShape(size * .32f)
    if (icon != null) {
        Box(Modifier.size(size).clip(shape).background(NeutralOverlay.copy(.05f)), contentAlignment = Alignment.Center) {
            Image(icon, null, Modifier.size(size * .68f))
        }
        return
    }
    val colors = listOf(Cyan, Violet2, Amber, Color(0xFFFF7C8E), Color(0xFF79A9FF))
    val color = colors[kotlin.math.abs(name.hashCode()) % colors.size]
    Box(Modifier.size(size).clip(shape).background(color.copy(.14f)), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull()?.uppercase() ?: "A", color = color, fontSize = (size.value * .4f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Country code of a server when its name or address reveals one; a globe otherwise. */
@Composable
internal fun ServerAvatar(node: ProxyNode?, size: Dp = 34.dp) {
    val code = node?.let(::avatarCode).orEmpty()
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .32f)).background(Violet.copy(.15f)), contentAlignment = Alignment.Center) {
        if (code.isBlank()) Icon(Icons.Rounded.Public, null, tint = Violet2, modifier = Modifier.size(size * .52f))
        else Text(code, color = Violet2, fontSize = (size.value * .32f).sp, fontWeight = FontWeight.Bold)
    }
}

/** Country of a server when its flag emoji or address suffix states it; used for the country filter. */
internal fun countryCode(node: ProxyNode): String {
    val name = PerRouteController.cleanName(node.name()).trim()
    val codePoints = name.codePoints().limit(2).toArray()
    if (codePoints.size == 2 && codePoints.all { it in 0x1F1E6..0x1F1FF }) {
        return String(codePoints.map { 'A'.code + (it - 0x1F1E6) }.toIntArray(), 0, 2)
    }
    val suffix = node.address().substringAfterLast('.', "").uppercase()
    return suffix.takeIf { it.length == 2 && it.all(Char::isLetter) } ?: ""
}

/** A looser guess used only to label the avatar: a standalone two-letter code such as "DE" in "Germany DE-01". */
private fun avatarCode(node: ProxyNode): String =
    countryCode(node).ifBlank { Regex("(?<![A-Za-z0-9])([A-Z]{2})(?![A-Za-z])").find(PerRouteController.cleanName(node.name()))?.groupValues?.get(1).orEmpty() }

/** A server name without the leading flag emoji, which Windows fonts cannot draw. */
internal fun serverName(node: ProxyNode): String {
    val name = PerRouteController.cleanName(node.name())
    val stripped = name.dropWhile { it.isSurrogate() || it.isWhitespace() }
    val points = name.codePoints().limit(2).toArray()
    return if (points.size == 2 && points.all { it in 0x1F1E6..0x1F1FF } && stripped.isNotBlank()) stripped.trim() else name
}

/** The protocol as users know it; an embedded Xray JSON profile is shown as "Xray JSON". */
internal fun protocolLabel(node: ProxyNode): String =
    if (node.protocol() == app.perroute.model.Protocol.XRAY_PROFILE) "Xray JSON" else node.protocol().name

/** An executable path as a readable application name. */
internal fun appName(path: String): String = PerRouteController.displayName(path)

internal fun fileName(path: String): String = runCatching { Path.of(path).fileName.toString() }.getOrDefault(path)

/** Joins names as "A, B and C", shortening long lists to "... and N more". */
internal fun joinNames(names: List<String>, limit: Int = 3): String = when {
    names.isEmpty() -> ""
    names.size == 1 -> names[0]
    names.size <= limit -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    else -> names.take(limit).joinToString(", ") + " and ${names.size - limit} more"
}

/** A count with its noun in the right number. */
internal fun plural(count: Int, singular: String, plural: String = singular + "s"): String = "$count ${if (count == 1) singular else plural}"

internal fun formatRate(bytes: Long): String = "${formatBytes(bytes)}/s"

/** A byte count in binary units. */
internal fun formatBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0L).toDouble()
    return when {
        value >= 1024.0 * 1024.0 * 1024.0 -> String.format(java.util.Locale.ROOT, "%.1f GB", value / (1024.0 * 1024.0 * 1024.0))
        value >= 1024.0 * 1024.0 -> String.format(java.util.Locale.ROOT, "%.1f MB", value / (1024.0 * 1024.0))
        value >= 1024.0 -> String.format(java.util.Locale.ROOT, "%.1f KB", value / 1024.0)
        else -> "${value.toLong()} B"
    }
}

internal fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remaining = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
    else "%02d:%02d".format(minutes, remaining)
}

/** How long ago something happened, in words. */
internal fun formatAgo(atMillis: Long, now: Long = System.currentTimeMillis()): String {
    val seconds = ((now - atMillis) / 1_000L).coerceAtLeast(0L)
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "$seconds seconds ago"
        seconds < 120 -> "1 minute ago"
        seconds < 3_600 -> "${seconds / 60} minutes ago"
        else -> "${seconds / 3_600} h ago"
    }
}

/** Lets a horizontal row be scrolled with the mouse wheel and by dragging. */
internal fun Modifier.wheelAndDragScroll(state: ScrollState): Modifier = this
    .pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                if (change.isConsumed) continue
                val delta = if (change.scrollDelta.x != 0f) change.scrollDelta.x else change.scrollDelta.y
                state.dispatchRawDelta(delta * 48.dp.toPx())
                change.consume()
            }
        }
    }
    .pointerInput(state) {
        detectHorizontalDragGestures { change, dragAmount ->
            change.consume()
            state.dispatchRawDelta(-dragAmount)
        }
    }

@Composable
internal fun CompositionLtr(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
