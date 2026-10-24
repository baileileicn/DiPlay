package com.shilapi.xcertplay.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shilapi.xcertplay.host.R

/**
 * The panel's edit mode over the same hero-and-grid surface the viewer shows: a chrome hint bar with
 * the drawer entry and Done, per-tile remove and size chips, and the add drawer grouped by what the
 * driver cares about. Reordering is a long-press drag (SidePanelDrag), so tiles carry no tap-swap.
 * Split from SidePanelCompose so the surface keeps one concern per file.
 */
@Composable
internal fun SidePanelEditor(
    model: SidePanelModel,
    palette: TilePalette,
    packed: SidePanelLayout.Packed,
    columns: Int,
    drawerOpen: Boolean,
    drag: DragState,
    scroll: ScrollState,
    edgePx: Float,
    stepPx: Float,
    setDrawerOpen: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(PANEL_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(PANEL_GAP_DP.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().panelChrome(palette).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                string(R.string.panel_edit_hint),
                color = palette.secondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(palette.accent.copy(alpha = 0.16f))
                    .combinedClickable(onClick = { setDrawerOpen(true) }),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = palette.accent, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                string(R.string.panel_done),
                color = palette.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(palette.accent.copy(alpha = 0.16f))
                    .combinedClickable(onClick = { setDrawerOpen(false); model.stopEditing() })
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        packed.hero?.let { hero ->
            val kind = TileCatalog.byId[hero.widget.kind] ?: return@let
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(panelHeroHeight())
                    .heroTile(palette, kind.group)
                    .tileDrag(kind.id, model, drag, scroll, edgePx, stepPx),
            ) { EditTileControls(kind, hero.size, palette, model) }
        }
        TileGridBody(packed.rest, columns) { item ->
            val kind = TileCatalog.byId[item.widget.kind]
            if (kind != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .glassTile(palette)
                        .tileDrag(kind.id, model, drag, scroll, edgePx, stepPx),
                ) { EditTileControls(kind, item.size, palette, model) }
            }
        }
    }
}

/** One tile's edit affordances: an identity row with a remove button, and the size chip beneath. */
@Composable
private fun EditTileControls(kind: TileKind, size: TileSize, palette: TilePalette, model: SidePanelModel) {
    Column(Modifier.fillMaxSize().padding(if (size.rowSpan > 1) 12.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(kind.group, palette, size = 22.dp, glyph = 13.dp)
            Spacer(Modifier.width(7.dp))
            Text(
                string(kind.titleRes).uppercase(),
                style = PanelType.label,
                color = palette.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(panelDanger(palette.night).copy(alpha = 0.14f))
                    .combinedClickable(onClick = { model.remove(kind.id) }),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = panelDanger(palette.night), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            sizeLabel(size),
            color = palette.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(palette.accent.copy(alpha = 0.14f))
                .combinedClickable(onClick = { model.cycleSize(kind.id) })
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun AddDrawer(model: SidePanelModel, palette: TilePalette, onDismiss: () -> Unit) {
    val placedIds = model.widgets.map { it.kind }.toSet()
    Column(
        Modifier
            .fillMaxSize()
            .background(palette.tileEdit)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (palette.night) 0.06f else 0.50f),
                    0.40f to Color.White.copy(alpha = 0f),
                ),
            )
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                string(R.string.panel_add_tiles),
                color = palette.primary,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(palette.secondary.copy(alpha = 0.12f))
                    .combinedClickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", color = palette.secondary, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            TileCatalog.grouped().forEach { (group, kinds) ->
                if (kinds.isEmpty()) return@forEach
                Spacer(Modifier.height(12.dp))
                Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(group, palette, size = 20.dp, glyph = 12.dp)
                    Spacer(Modifier.width(7.dp))
                    Text(
                        groupName(group).uppercase(),
                        style = PanelType.label,
                        color = palette.secondary,
                        maxLines = 1,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { kind ->
                                val added = kind.id in placedIds
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .glassTile(palette, selected = added)
                                        .combinedClickable(onClick = { if (added) model.remove(kind.id) else model.add(kind.id) })
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(
                                        string(kind.titleRes),
                                        color = if (added) palette.accent else palette.primary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            repeat((2 - row.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

internal fun sizeLabel(size: TileSize): String = when (size) {
    TileSize.SMALL -> "S"; TileSize.WIDE -> "W"; TileSize.TALL -> "T"; TileSize.LARGE -> "L"
}
