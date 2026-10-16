# Side panel rework design: hero + compact tiles, long-press drag (2026-10-16)

Confirmed with the driver through four browser-mockup review rounds. Replaces the
tap-two-to-swap editor and the uniform 84 dp card grid.

## Layout and visual spec

- **Hero** is the first widget in queue order (`widgets[0]`): full panel width,
  ~118 dp tall, 36 sp value, accent-tinted border on `palette.tileEdit`, status
  colour strip and stale dimming preserved. A progress arc is drawn only when the
  readout carries a natural fraction (state of charge, fuel); speed and clock do not.
- **Compact tiles** replace the 84 dp card with a 60 dp row: a 10 sp label and the
  17 sp value share one line (value reuses the inline unit styling); gap 8 → 6 dp;
  `rowSpan 2` tiles render at 126 dp. MULTI/TYRES tiles keep their cells and place
  the tile label in a narrow left column; SYSTEM at rowSpan 1 degrades to one text
  line. The per-tile icon+title header (`TileHeader`) is removed.
- **Group captions** (`GroupCaption`, e.g. "行驶") are deleted; grouping survives
  only in the add drawer.
- Metrics live as named constants: `PANEL_ROW_H_DP = 60`, `PANEL_TALL_H_DP = 126`,
  `PANEL_HERO_H_DP = 118`, `PANEL_GAP_DP = 6`.

## Interaction spec

- Long-press 350 ms lifts the tile (scale 1.06, shadow, accent outline) and enters
  edit mode in the same gesture; movement > 12 dp before the threshold cancels the
  lift (plain scroll). Lifted state is keyed by widget kind, so refresh ticks
  during a drag cannot drop or scramble it.
- Dragging is finger-following with a dashed accent **drop slot** showing the
  insertion index; the grid does not reflow mid-drag — the slot is an overlay.
  Near the top/bottom 60 dp the list auto-scrolls with graduated speed.
- Release calls `SidePanelModel.move(from, to)` once and persists. Dropping at
  index 0 makes the tile the hero; dragging the hero elsewhere returns it to its
  stored compact size. Pointer cancel springs the tile back without persisting.
- Edit mode stays open after the drop: per-tile `×` remove and size chip
  (S/W/T/L tap-to-cycle), top bar `+` drawer and Done. **Tap-two-to-swap is
  deleted**, removing its off-by-one move and the size-swap surprise.
- Queue of one tile: long-press still opens edit mode but does not lift.

## Architecture

- `SidePanelLayout.packWithHero(widgets, cols)` returns `Packed(hero, rest)`; the
  existing `pack()` is untouched and keeps packing the rest. Pure Kotlin, unit
  tested on 1–4 columns.
- `SidePanelModel.move(from, to)`: remove-at then insert-at-clamped, persisted;
  `tapInEdit`/`selected` deleted. `cycleSize` remains for the size chip.
- `SidePanelDrag.kt` holds the drag state machine (lift, target index from cached
  `boundsInRoot` geometry, auto-scroll); `SidePanelEditor.kt` holds the editor
  top bar and add drawer moved out of the oversized `SidePanelCompose.kt`.
- Persistence format (`id[SIZE];…`, first entry = hero) is unchanged: existing
  saved layouts open with their first tile as hero, no migration.

## Testing and acceptance

- New unit tests: `packWithHero` (hero separation, empty, single) and `move`
  (to front, adjacent, no-op, clamp). Existing layout tests keep passing.
- Full CI command green (the ten environment-bound pre-existing failures on this
  machine are excluded; their count must not grow).
- Driver acceptance on the car: ≥ 8 tiles visible per screen, hero full-width,
  no group captions, drag reorder with slot preview and hero swap, old saved
  layout opens correctly, dark/light and Arabic spot checks.
