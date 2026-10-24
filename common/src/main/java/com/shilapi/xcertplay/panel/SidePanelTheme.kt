package com.shilapi.xcertplay.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The panel's rhythm: one set of dp constants so the grid, the hero and the editor share it. */
internal const val PANEL_ROW_H_DP = 80
internal const val PANEL_HERO_H_DP = 140
internal const val PANEL_GAP_DP = 10
internal const val PANEL_PADDING_DP = 16
internal const val PANEL_HERO_RADIUS_DP = 28
internal const val PANEL_TILE_RADIUS_DP = 24

/** Heights grow with the system font scale, so a large-font setting reflows the grid instead of clipping it. */
@Composable
internal fun panelRowHeight(): Dp = (PANEL_ROW_H_DP * (1f + fontExtra() * 0.5f)).dp

@Composable
internal fun panelHeroHeight(): Dp = (PANEL_HERO_H_DP * (1f + fontExtra())).dp

@Composable
private fun fontExtra(): Float = (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f)

/**
 * iOS-style continuous corners: the curve leaves each edge tangentially and tightens into the corner,
 * where a circular [androidx.compose.foundation.shape.RoundedCornerShape] would meet it at an angle.
 * A cubic with control points at 45% of the radius approximates Apple's squircle closely enough at
 * these sizes and stays a plain path, so it costs nothing to draw on Android 10.
 */
private fun continuousShape(radiusPx: Float): Shape = GenericShape { size, _ ->
    val r = radiusPx.coerceAtMost(minOf(size.width, size.height) / 2f)
    val c = r * 0.45f
    moveTo(r, 0f)
    lineTo(size.width - r, 0f)
    cubicTo(size.width - r + c, 0f, size.width, r - c, size.width, r)
    lineTo(size.width, size.height - r)
    cubicTo(size.width, size.height - r + c, size.width - r + c, size.height, size.width - r, size.height)
    lineTo(r, size.height)
    cubicTo(r - c, size.height, 0f, size.height - r + c, 0f, size.height - r)
    lineTo(0f, r)
    cubicTo(0f, r - c, r - c, 0f, r, 0f)
    close()
}

@Composable
internal fun panelShape(radius: Dp): Shape {
    val px = with(LocalDensity.current) { radius.toPx() }
    return remember(px) { continuousShape(px) }
}

/** Which layer of the material stack a surface belongs to. */
internal enum class PanelLevel { Hero, Tile, Chrome }

/**
 * A frosted surface without a backdrop blur: a translucent fill the panel card shows through, a
 * top-edge highlight that reads as glass, an optional category wash, and a hairline edge. Hero and
 * chrome layers also carry a soft shadow in the light theme, where depth comes from elevation; the
 * dark theme stays flat and separates by tone alone, as iOS does.
 */
@Composable
internal fun Modifier.panelSurface(
    palette: TilePalette,
    level: PanelLevel = PanelLevel.Tile,
    selected: Boolean = false,
    tint: Color? = null,
): Modifier {
    val radius = if (level == PanelLevel.Hero) PANEL_HERO_RADIUS_DP.dp else PANEL_TILE_RADIUS_DP.dp
    val shape = panelShape(radius)
    val fill = if (selected || level != PanelLevel.Tile) palette.tileEdit else palette.tile
    val highlight = Color.White.copy(alpha = if (palette.night) 0.10f else 0.35f)
    val edge = when {
        selected -> palette.accent.copy(alpha = 0.55f)
        tint != null -> tint.copy(alpha = 0.45f)
        else -> palette.tileBorder
    }
    var surface = this
    if (!palette.night && level != PanelLevel.Tile) surface = surface.shadow(6.dp, shape, clip = false)
    surface = surface.clip(shape).background(fill)
    if (tint != null) surface = surface.background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0f))), shape)
    surface = surface.background(Brush.verticalGradient(0f to highlight, 0.55f to highlight.copy(alpha = 0f)), shape)
    return surface.border(BorderStroke(if (level == PanelLevel.Hero) 1.5.dp else 1.dp, edge), shape)
}

@Composable
internal fun Modifier.glassTile(palette: TilePalette, selected: Boolean = false, tint: Color? = null): Modifier =
    panelSurface(palette, PanelLevel.Tile, selected, tint)

@Composable
internal fun Modifier.heroTile(palette: TilePalette, group: TileGroup): Modifier =
    panelSurface(palette, PanelLevel.Hero, tint = group.hue(palette.night).copy(alpha = 0.16f))

@Composable
internal fun Modifier.panelChrome(palette: TilePalette): Modifier =
    panelSurface(palette, PanelLevel.Chrome)

/** The iOS system colour that identifies a group; the same hue tints its icon badge and hero wash. */
internal fun TileGroup.hue(night: Boolean): Color = when (this) {
    TileGroup.CLOCK -> if (night) Color(0xFFFFFFFF) else Color(0xFF1C1C1E)
    TileGroup.ENERGY -> Color(if (night) 0xFF30D158 else 0xFF34C759)
    TileGroup.DRIVE -> Color(if (night) 0xFF0A84FF else 0xFF007AFF)
    TileGroup.CLIMATE -> Color(if (night) 0xFFFF9F0A else 0xFFFF9500)
    TileGroup.HYBRID -> Color(if (night) 0xFF40C8E0 else 0xFF30B0C7)
    TileGroup.TECH -> Color(if (night) 0xFFBF5AF2 else 0xFFAF52DE)
    TileGroup.BODY -> Color(if (night) 0xFF5E5CE6 else 0xFF5856D6)
    TileGroup.LIGHTS -> Color(if (night) 0xFFFFD60A else 0xFFFFCC00)
    TileGroup.SAFETY -> Color(if (night) 0xFFFF453A else 0xFFFF3B30)
    TileGroup.SYSTEM -> Color(0xFF8E8E93)
}

internal fun panelWarn(night: Boolean): Color = Color(if (night) 0xFFFF9F0A else 0xFFFF9500)

internal fun panelGood(night: Boolean): Color = Color(if (night) 0xFF30D158 else 0xFF34C759)

internal fun panelDanger(night: Boolean): Color = Color(if (night) 0xFFFF453A else 0xFFFF3B30)

/** Type scale: large numerals tighten their tracking and use tabular figures so a refresh never shifts them. */
internal object PanelType {
    fun hero() = TextStyle(
        fontSize = 46.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-1).sp,
        fontFeatureSettings = "tnum",
    )

    fun tall() = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.6).sp,
        fontFeatureSettings = "tnum",
    )

    fun standard() = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum",
    )

    fun cell() = TextStyle(
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum",
    )

    val label = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    val cellLabel = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp)
}

/** A group's icon in a tinted circle: the one place a category colour appears on a resting tile. */
@Composable
internal fun IconBadge(group: TileGroup, palette: TilePalette, size: Dp = 28.dp, glyph: Dp = 16.dp) {
    val hue = group.hue(palette.night)
    Box(
        Modifier.size(size).clip(CircleShape).background(hue.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(group.iconRes()),
            contentDescription = null,
            colorFilter = ColorFilter.tint(hue),
            modifier = Modifier.size(glyph),
        )
    }
}
