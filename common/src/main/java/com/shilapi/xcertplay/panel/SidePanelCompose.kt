package com.shilapi.xcertplay.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydPanelFields
import com.shilapi.xcertplay.hud.BydPanelRepository
import com.shilapi.xcertplay.hud.BydTier
import com.shilapi.xcertplay.hud.BydTyreReading
import com.shilapi.xcertplay.hud.BydTyreStatus
import java.util.Calendar
import kotlin.math.roundToInt

/** Colours the host passes in, so tiles follow the app theme without reading resources here. */
data class TilePalette(
    val secondary: Color,
    val primary: Color,
    val accent: Color,
    val warn: Color,
    val good: Color,
    /** A tile's translucent glass fill; the card behind it shows through faintly. */
    val tile: Color,
    /** A selected tile's fill, brighter than [tile]. */
    val tileEdit: Color,
    /** The inner hairline that gives a tile its frosted edge, light on dark and dark on light. */
    val tileBorder: Color,
    /** True for the night theme: surfaces separate by tone, and shadows belong to the light theme. */
    val night: Boolean = false,
)

/**
 * The side panel's surface: the tile grid the driver laid out, an editor over the same tiles, and the add
 * drawer. It reads [SidePanelModel]'s snapshot state and re-composes on a slow tick, or a fast one when a
 * live tile (pedals, speed) is placed, so the values update without the host rebuilding views. Column count
 * follows the panel's own width, so 1/3, 1/2 and 2/3 layouts differ; a long list scrolls rather than clipping.
 */
@Composable
fun SidePanelPanel(model: SidePanelModel, palette: TilePalette) {
    var drawerOpen by remember { mutableStateOf(false) }
    val drag = remember { DragState() }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { drag.panelBounds = it.boundsInWindow() },
    ) {
        val columns = SidePanelLayout.columns(maxWidth.value.roundToInt())
        val live = remember(model.widgets.toList()) { model.widgets.any { TileCatalog.byId[it.kind]?.tier() == BydTier.FAST } }
        val frame = remember { mutableIntStateOf(0) }
        LaunchedEffect(live) {
            while (true) {
                kotlinx.coroutines.delay(if (live) 500L else 5_000L)
                frame.intValue++
            }
        }
        val packed = remember(model.widgets.toList(), columns, frame.intValue, model.revision) {
            SidePanelLayout.packWithHero(model.widgets.toList(), columns)
        }
        val edgePx = with(density) { 60.dp.toPx() }
        val stepPx = with(density) { 24.dp.toPx() }
        if (model.editing) {
            SidePanelEditor(model, palette, packed, columns, drawerOpen, drag, scroll, edgePx, stepPx) { drawerOpen = it }
        } else {
            SidePanelGrid(model, palette, packed, columns, drag, scroll, edgePx, stepPx)
        }
        DropSlot(model, drag, palette)
        if (drawerOpen) AddDrawer(model, palette) { drawerOpen = false }
    }
}

@Composable
private fun SidePanelGrid(
    model: SidePanelModel,
    palette: TilePalette,
    packed: SidePanelLayout.Packed,
    columns: Int,
    drag: DragState,
    scroll: ScrollState,
    edgePx: Float,
    stepPx: Float,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(PANEL_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        packed.hero?.let { hero ->
            val kind = TileCatalog.byId[hero.widget.kind]
            if (kind != null) Box(
                Modifier
                    .fillMaxWidth()
                    .height(panelHeroHeight())
                    .heroTile(palette, kind.group)
                    .tileDrag(kind.id, model, drag, scroll, edgePx, stepPx),
            ) {
                if (kind.format == TileFormat.VALUE || kind.format == TileFormat.BATTERY || kind.format == TileFormat.CLOCK) {
                    HeroValueTile(kind, palette)
                } else {
                    TileContent(kind, hero, palette)
                }
                if (drag.liftedKind != null) DragAffordances(palette, hero.size)
            }
        }
        TileGridBody(packed.rest, columns) { item ->
            val kind = TileCatalog.byId[item.widget.kind]
            if (kind != null) Box(
                Modifier
                    .fillMaxSize()
                    .glassTile(palette)
                    .tileDrag(kind.id, model, drag, scroll, edgePx, stepPx),
            ) {
                TileContent(kind, item, palette)
                if (drag.liftedKind != null) DragAffordances(palette, item.size)
            }
        }
    }
}

/**
 * The tile grid with true row and column spans: the packer already reserved a spanning tile's cells, so
 * each tile is placed at its (row, col) with the track it covers, and a tall tile's second row stays open
 * for the tiles packed beside it. A Row-per-row layout would push everything below a tall tile instead.
 */
@Composable
internal fun TileGridBody(
    items: List<SidePanelLayout.Placed>,
    columns: Int,
    content: @Composable (SidePanelLayout.Placed) -> Unit,
) {
    if (items.isEmpty()) return
    val gap = PANEL_GAP_DP.dp
    val rowH = panelRowHeight()
    val rows = items.maxOf { it.row + it.rowSpan }
    BoxWithConstraints(Modifier.fillMaxWidth().height(rowH * rows + gap * (rows - 1))) {
        val trackW = (maxWidth - gap * (columns - 1)) / columns
        Layout(content = { items.forEach { content(it) } }) { measurables, constraints ->
            val gapPx = gap.toPx()
            val trackPx = trackW.toPx()
            val rowPx = rowH.toPx()
            val placeables = measurables.mapIndexed { index, measurable ->
                val item = items[index]
                measurable.measure(
                    Constraints.fixed(
                        (trackPx * item.colSpan + gapPx * (item.colSpan - 1)).roundToInt(),
                        (rowPx * item.rowSpan + gapPx * (item.rowSpan - 1)).roundToInt(),
                    ),
                )
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val item = items[index]
                    placeable.place(
                        ((trackPx + gapPx) * item.col).roundToInt(),
                        ((rowPx + gapPx) * item.row).roundToInt(),
                    )
                }
            }
        }
    }
}

/**
 * Decorative edit affordances on every tile while a lift is live, so the browse surface shows the
 * same × and size chip the frame calls for; the real editor opens when the drop lands.
 */
@Composable
private fun BoxScope.DragAffordances(palette: TilePalette, size: TileSize) {
    Text(
        "×",
        color = palette.warn,
        fontSize = 15.sp,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 5.dp, end = 7.dp)
            .clip(CircleShape),
    )
    Text(
        sizeLabel(size),
        color = palette.accent,
        fontSize = 11.sp,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 7.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(BorderStroke(1.dp, palette.tileBorder), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The hero's content: category wash and watermark behind a label row, an oversized value and a capacity bar. */
@Composable
private fun HeroValueTile(kind: TileKind, palette: TilePalette) {
    val readout = readoutFor(kind)
    val hue = kind.group.hue(palette.night)
    val accent = accentColor(readout, palette, kind.group)
    val primary = readout.primaryRes
    val primaryText = if (primary != null) string(primary) else readout.primary
    val unitColor = if (readout.accent == TileAccent.NONE) palette.secondary else accent
    Box(Modifier.fillMaxSize()) {
        Image(
            painterResource(kind.group.iconRes()),
            contentDescription = null,
            colorFilter = ColorFilter.tint(hue),
            alpha = 0.07f,
            modifier = Modifier.align(Alignment.BottomEnd).offset(x = 14.dp, y = 18.dp).size(104.dp),
        )
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(kind.group, palette, size = 32.dp, glyph = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    string(kind.titleRes).uppercase(),
                    style = PanelType.label,
                    color = palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                valueWithUnit(primaryText, readout.unit, unitColor, unitSize = 14.sp),
                style = PanelType.hero(),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            readout.secondary?.let { if (readout.fraction == null) Text(it, color = palette.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            readout.fraction?.let { fraction ->
                Spacer(Modifier.height(10.dp))
                CapacityBar(fraction, accent, 6.dp)
            }
        }
    }
}

/** The group icons, reused by tile headers and the drawer. */
internal fun TileGroup.iconRes(): Int = when (this) {
    TileGroup.CLOCK -> R.drawable.ic_dp_clock
    TileGroup.ENERGY -> R.drawable.ic_dp_automation
    TileGroup.DRIVE -> R.drawable.ic_dp_dashboard
    TileGroup.CLIMATE -> R.drawable.ic_dp_controls
    TileGroup.HYBRID -> R.drawable.ic_dp_vehicle
    TileGroup.TECH -> R.drawable.ic_dp_advanced
    TileGroup.LIGHTS -> R.drawable.ic_dp_day
    TileGroup.SAFETY -> R.drawable.ic_dp_permissions
    TileGroup.SYSTEM -> R.drawable.ic_dp_display
    TileGroup.BODY -> R.drawable.ic_dp_car
}

@Composable
private fun TileContent(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    if (kind.format == TileFormat.TYRES) { TyresTile(kind, item, palette); return }
    if (kind.format == TileFormat.SYSTEM) { SystemTile(kind, item, palette); return }
    if (kind.format == TileFormat.MULTI) { MultiTile(kind, item, palette); return }
    ValueTile(kind, item, palette)
}

/**
 * A single-value tile: a tinted icon badge and an uppercase label share a row with the value; a tall
 * tile stacks the same parts over a subline and a capacity bar. A live signal washes the whole tile
 * with its category colour, so an active tile reads at a glance without every tile shouting.
 */
@Composable
private fun ValueTile(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    val readout = readoutFor(kind)
    val accent = accentColor(readout, palette, kind.group)
    val active = readout.present && !readout.stale && readout.accent == TileAccent.LIVE
    val primary = readout.primaryRes
    val primaryText = if (primary != null) string(primary) else readout.primary
    val unitColor = if (readout.accent == TileAccent.NONE) palette.secondary else accent
    Box(Modifier.fillMaxSize()) {
        if (active) {
            Box(Modifier.matchParentSize().background(kind.group.hue(palette.night).copy(alpha = 0.10f)))
        }
        if (item.rowSpan == 1) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBadge(kind.group, palette, size = 28.dp, glyph = 16.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        string(kind.titleRes).uppercase(),
                        style = PanelType.label,
                        color = palette.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        valueWithUnit(primaryText, readout.unit, unitColor, unitSize = 12.sp),
                        style = PanelType.standard(),
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(kind.group, palette, size = 26.dp, glyph = 15.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        string(kind.titleRes).uppercase(),
                        style = PanelType.label,
                        color = palette.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column {
                    Text(
                        valueWithUnit(primaryText, readout.unit, unitColor, unitSize = 12.sp),
                        style = PanelType.tall(),
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    readout.secondary?.let { Text(it, color = palette.secondary, fontSize = 12.sp, maxLines = 1) }
                    readout.fraction?.let { fraction ->
                        Spacer(Modifier.height(8.dp))
                        CapacityBar(fraction, accent, 6.dp)
                    }
                }
            }
        }
    }
}

/**
 * The big value with its unit set small on the same line, so the number keeps the visual weight:
 * "120 km/h", "63%". The percent sign hugs its number; other units get a space.
 */
private fun valueWithUnit(primary: String, unit: String, unitColor: Color, unitSize: TextUnit): AnnotatedString {
    if (unit.isEmpty()) return AnnotatedString(primary)
    val glue = if (unit == "%") "" else " "
    return buildAnnotatedString {
        append(primary)
        withStyle(SpanStyle(fontSize = unitSize, fontWeight = FontWeight.Normal, color = unitColor)) {
            append(glue + unit)
        }
    }
}

/** A grouped tile: a badge header and one labelled cell per signal; state signals become pills. */
@Composable
private fun MultiTile(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    val reads = kind.fields.associateWith { readValue(it) }
    val cells = TileFormatter.cells(kind.fields, kind.labels, reads.mapValues { it.value.first })
        .mapIndexed { index, cell ->
            if (reads.getValue(kind.fields[index]).second) cell.copy(stale = true) else cell
        }
    val big = item.rowSpan > 1
    val rows = if (big || cells.size > 3) {
        listOf(cells.take(2), cells.drop(2)).filter { it.isNotEmpty() }
    } else {
        listOf(cells)
    }
    val active = cells.any { it.accent == TileAccent.LIVE && !it.stale }
    Box(Modifier.fillMaxSize()) {
        if (active) {
            Box(Modifier.matchParentSize().background(kind.group.hue(palette.night).copy(alpha = 0.10f)))
        }
        Column(
            Modifier.fillMaxSize().padding(if (big) 14.dp else 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(kind.group, palette, size = if (big) 24.dp else 20.dp, glyph = if (big) 14.dp else 12.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    string(kind.titleRes).uppercase(),
                    style = PanelType.label,
                    color = palette.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (big) 8.dp else 4.dp)) {
                        row.forEach { cell ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                MultiCell(cell, palette, kind.group, big)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiCell(cell: TileCell, palette: TilePalette, group: TileGroup, big: Boolean) {
    val color = when {
        cell.stale -> palette.secondary
        cell.accent == TileAccent.WARN -> panelWarn(palette.night)
        cell.accent == TileAccent.GOOD -> panelGood(palette.night)
        cell.accent == TileAccent.LIVE -> group.hue(palette.night)
        else -> palette.primary
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.heightIn(min = if (big) 26.dp else 24.dp), contentAlignment = Alignment.Center) {
            if (cell.pill) {
                StatePill(cell, color, palette, big)
            } else {
                Text(
                    cell.valueRes?.let { string(it) } ?: cell.value,
                    style = PanelType.cell(),
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (cell.label.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(cell.label, style = PanelType.cellLabel, color = palette.secondary, maxLines = 1)
        }
    }
}

/** A state cell as a tinted capsule; a seat's bullet or a missing value draws as a filled or hollow dot. */
@Composable
private fun StatePill(cell: TileCell, color: Color, palette: TilePalette, big: Boolean) {
    val neutral = cell.accent == TileAccent.NONE || cell.stale
    val text = cell.valueRes?.let { string(it) } ?: cell.value
    if (text == "•" || text == "—") {
        val filled = text == "•"
        Box(
            Modifier
                .size(if (big) 12.dp else 10.dp)
                .clip(CircleShape)
                .background(if (filled) color else Color.Transparent)
                .border(BorderStroke(1.5.dp, if (filled) color else palette.tileBorder), CircleShape),
        )
        return
    }
    val tint = if (neutral) palette.secondary else color
    Text(
        text.uppercase(),
        fontSize = if (big) 10.sp else 9.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun CapacityBar(fraction: Float, color: Color, height: Dp) {
    val shape = RoundedCornerShape(height / 2)
    Box(Modifier.fillMaxWidth().height(height).clip(shape).background(color.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(shape).background(color))
    }
}

/**
 * The head unit's own load, read locally (no adb): CPU share and memory share each with a bar, the fastest
 * core's frequency, and the hottest thermal zone when the kernel lets a normal app read it. GPU is not shown:
 * Android 10 has no API a plain app can use to read it.
 */
@Composable
private fun SystemTile(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    val reading = SystemStatus.latest()
    val big = item.rowSpan > 1
    Column(Modifier.fillMaxSize().padding(if (big) 14.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(if (big) 6.dp else 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(kind.group, palette, size = if (big) 24.dp else 20.dp, glyph = if (big) 14.dp else 12.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                string(kind.titleRes).uppercase(),
                style = PanelType.label,
                color = palette.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (reading == null) {
            Text("—", style = PanelType.cell(), color = palette.secondary)
        } else {
            loadRow("CPU", "${reading.cpuPercent.roundToInt()}%", reading.cpuPercent / 100.0, palette, big)
            val memFraction = if (reading.memTotalGb > 0.0) reading.memUsedGb / reading.memTotalGb else 0.0
            loadRow("RAM", "%.1f/%.0f".format(reading.memUsedGb, reading.memTotalGb), memFraction, palette, big)
            val detail = buildList {
                reading.coreFreqMhz.maxOrNull()?.let { add(it / 1000.0 to "GHz") }
                reading.tempC?.let { add(it to "°C") }
            }
            if (detail.isNotEmpty()) {
                Text(
                    detail.joinToString(" · ") { (value, unit) -> if (unit == "GHz") "%.2f GHz".format(value) else "%.0f°C".format(value) },
                    color = palette.secondary, fontSize = 11.sp, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun loadRow(label: String, value: String, fraction: Double, palette: TilePalette, big: Boolean) {
    val hot = fraction >= 0.85
    val color = if (hot) panelWarn(palette.night) else palette.accent
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = PanelType.cellLabel, color = palette.secondary, modifier = Modifier.weight(1f))
            Text(value, color = if (hot) color else palette.primary, fontSize = if (big) 16.sp else 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = 0.18f))) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat()).fillMaxHeight()
                .clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/** The 2×2 tyre block: front pair on top, rear pair below, each with pressure over temperature. */
@Composable
private fun TyresTile(kind: TileKind, item: SidePanelLayout.Placed, palette: TilePalette) {
    val tyres = BydTyreStatus.latest()
    val big = item.size == TileSize.LARGE || item.size == TileSize.TALL
    Column(Modifier.fillMaxSize().padding(if (big) 14.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(kind.group, palette, size = if (big) 24.dp else 20.dp, glyph = if (big) 14.dp else 12.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                string(kind.titleRes).uppercase(),
                style = PanelType.label,
                color = palette.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(if (big) 10.dp else 4.dp))
        if (tyres == null) {
            Text("—", style = PanelType.cell(), color = palette.secondary)
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (big) 8.dp else 3.dp)) {
                tyrePair(tyres, 0, 1, palette, big)
                tyrePair(tyres, 2, 3, palette, big)
            }
        }
    }
}

@Composable
private fun tyrePair(tyres: BydTyreReading, left: Int, right: Int, palette: TilePalette, big: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        tyreCell(tyres, left, palette, big)
        tyreCell(tyres, right, palette, big)
    }
}

@Composable
private fun tyreCell(tyres: BydTyreReading, index: Int, palette: TilePalette, big: Boolean) {
    val pressure = tyres.pressureKpa.getOrNull(index)?.let { "$it" } ?: "—"
    Column {
        Text(pressure, style = if (big) PanelType.cell() else PanelType.standard(), color = palette.primary, maxLines = 1)
        tyres.temperatureC.getOrNull(index)?.let { if (big) Text("$it°C", color = palette.secondary, fontSize = 11.sp, maxLines = 1) }
    }
}

private fun accentColor(readout: TileReadout, palette: TilePalette, group: TileGroup): Color = when {
    readout.stale -> palette.secondary
    readout.accent == TileAccent.WARN -> panelWarn(palette.night)
    readout.accent == TileAccent.GOOD -> panelGood(palette.night)
    readout.accent == TileAccent.LIVE -> group.hue(palette.night)
    readout.present -> palette.primary
    else -> palette.secondary
}

/**
 * A field's value and whether it is only a kept-but-stale reading: [BydPanelRepository.stale] serves
 * values past the fresh window so a quiet field dims rather than blanking while reads are down.
 */
private fun readValue(id: String): Pair<Double?, Boolean> {
    BydPanelRepository.latest(id)?.let { return it to false }
    BydPanelRepository.stale(id)?.let { return it to true }
    return null to false
}

/** The value a tile shows, read fresh so each recomposition (the live tick or the model revision) picks it up. */
private fun readoutFor(kind: TileKind): TileReadout {
    val stale = kind.fields.firstOrNull()?.let { readValue(it).second } ?: false
    val readout = when (kind.format) {
        TileFormat.CLOCK -> {
            val now = Calendar.getInstance()
            TileReadout("%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)))
        }
        TileFormat.BATTERY -> {
            // The built-in card has no field list of its own: it shows SOC over range from the repository.
            val (soc, socStale) = readValue("soc")
            val (range, _) = readValue("evRange")
            TileReadout(
                if (soc == null) "—" else "${soc.roundToInt()}%",
                unit = if (range == null) "" else "· ${range.roundToInt()} km",
                accent = if (soc != null && soc <= 15) TileAccent.WARN else TileAccent.NONE,
                present = soc != null,
                stale = soc != null && socStale,
            )
        }
        TileFormat.TYRES -> TileReadout("—", present = BydTyreStatus.latest() != null)
        else -> {
            val values = kind.fields.associateWith { readValue(it).first }
            val base = if (kind.composite) {
                TileFormatter.composite(kind.fields.first(), values[kind.fields.first()], kind.fields.getOrNull(1), values[kind.fields.getOrNull(1)])
            } else {
                TileFormatter.value(kind.fields.firstOrNull() ?: "", values.values.firstOrNull())
            }
            if (stale) base.copy(stale = true) else base
        }
    }
    return readout
}

/** The tier to poll for a tile: fast if any of its signals is, slow only when all are. */
private fun TileKind.tier(): BydTier? {
    val tiers = fields.mapNotNull { BydPanelFields.byId[it]?.tier }
    return when {
        tiers.isEmpty() -> null
        tiers.any { it == BydTier.FAST } -> BydTier.FAST
        tiers.all { it == BydTier.SLOW } -> BydTier.SLOW
        else -> BydTier.NORMAL
    }
}

@Composable
internal fun groupName(group: TileGroup): String = string(when (group) {
    TileGroup.CLOCK -> R.string.panel_group_clock
    TileGroup.ENERGY -> R.string.panel_group_energy
    TileGroup.DRIVE -> R.string.panel_group_drive
    TileGroup.CLIMATE -> R.string.panel_group_climate
    TileGroup.HYBRID -> R.string.panel_group_hybrid
    TileGroup.TECH -> R.string.panel_group_tech
    TileGroup.LIGHTS -> R.string.panel_group_lights
    TileGroup.SAFETY -> R.string.panel_group_safety
    TileGroup.SYSTEM -> R.string.panel_group_system
    TileGroup.BODY -> R.string.panel_group_body
})

/** A string resource, read from the composition's context. */
@Composable
internal fun string(res: Int): String = LocalContext.current.getString(res)
