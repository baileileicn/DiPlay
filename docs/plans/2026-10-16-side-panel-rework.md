# 侧面板重设计（Hero+紧凑块 + 长按拖拽）实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 把 CarPlay 侧面板升级为"Hero + 紧凑块"高密度布局，删除分组小标题，用长按拖拽替换有缺陷的点两下交换。

**Architecture:** `SidePanelLayout.packWithHero()` 把队列第一块分离为 Hero（全宽），其余照旧排格；`SidePanelCompose` 用紧凑行高（60dp）渲染无头卡片；拖拽为独立 UI 状态机（`SidePanelDrag.kt`），松手仅调用 `SidePanelModel.move(from,to)` 一次落库。持久化格式零改动（首条即 Hero）。

**Tech Stack:** Kotlin · Jetpack Compose（现有依赖）· SharedPreferences 存储 · JUnit4 单测（纯 JVM）

**设计依据（已与用户逐节确认）：**
1. 布局：Hero=队列第一块（~118dp 全宽、36sp 大数字，有天然占比的量画进度弧）；紧凑块 60dp 行高、标签(10sp)+数值(17sp)同行；分组小标题（GroupCaption）删除；警示色条/过期变灰/状态词机制保留
2. 交互：长按 350ms 抬起（放大1.06+阴影+accent描边）并同时进入编辑态；跟手拖动+虚线落点坑（拖动中不重排，松手才 move）；边缘 60dp 渐速自动滚动；掉到第 0 位=Hero；编辑态保持打开（×/尺寸胶囊/+/完成）；删除点两下交换
3. 不做：设为主角按钮、撤销、手势时长设置项（YAGNI）

**约束：**
- 不自动 commit（用户决定）
- 全套 CI：`./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest :mobile:lintDebug :home:lintDebug :maphost:lintDebug :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug`
- 已知先期失败 10 例（hotspot/auth/home 布局类，本机环境性），基线一致，不属于本计划回归范围
- 仓库根：`F:\Carplay\work\merged-main`

---

### Task 0: 落盘文档与卫生

**Files:**
- Modify: `.gitignore`（仓库根）
- Create: `docs/superpowers/specs/2026-10-16-side-panel-rework-design.md`
- Create: `docs/plans/2026-10-16-side-panel-rework.md`（本计划副本）

**Step 0.1** `.gitignore` 追加一行：
```
.superpowers/
```

**Step 0.2** 将本计划的设计四节（布局规格/拖拽四帧/架构/测试）整理为设计文档写入 `docs/superpowers/specs/2026-10-16-side-panel-rework-design.md`（含浏览器 mockup 的文字化规格：Hero 118dp、行高 60dp、间距 6dp、抬起 1.06/350ms/12dp、边缘 60dp 自动滚）。

**Step 0.3** 将本计划复制到 `docs/plans/2026-10-16-side-panel-rework.md`。

**Step 0.4** 验证：`rtk git status` 仅新增上述文件与 `.gitignore` 修改。

---

### Task 1: `packWithHero`（TDD，纯逻辑）

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelLayout.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/panel/SidePanelLayoutTest.kt`（现有 5 个 pack 测试不动）

**Step 1.1 写失败测试**（追加到 SidePanelLayoutTest，复用现有 `w()` 帮助函数）：
```kotlin
@Test
fun firstWidgetIsHeroAndRestPacks() {
    val widgets = listOf(w("clock", TileSize.WIDE), w("soc", TileSize.SMALL), w("gear", TileSize.SMALL))
    val packed = SidePanelLayout.packWithHero(widgets, 2)
    assertEquals("clock", packed.hero?.widget?.kind)
    assertEquals(2, packed.hero?.colSpan)
    assertEquals(listOf("soc", "gear"), packed.rest.map { it.widget.kind })
}

@Test
fun emptyListHasNoHero() {
    val packed = SidePanelLayout.packWithHero(emptyList(), 2)
    assertNull(packed.hero)
    assertTrue(packed.rest.isEmpty())
}

@Test
fun singleWidgetIsOnlyHero() {
    val packed = SidePanelLayout.packWithHero(listOf(w("clock", TileSize.WIDE)), 2)
    assertEquals("clock", packed.hero?.widget?.kind)
    assertTrue(packed.rest.isEmpty())
}
```

**Step 1.2 运行确认失败**：
`./gradlew :common:testDebugUnitTest --tests "com.shilapi.xcertplay.panel.SidePanelLayoutTest" --console=plain`
预期：编译错误（`packWithHero`/`Packed` 不存在）。

**Step 1.3 最小实现**（SidePanelLayout.kt 追加）：
```kotlin
data class Packed(val hero: Placed?, val rest: List<Placed>)

/** [widgets]'s first is the hero card (full-width, rendered alone); the rest pack on the grid. */
fun packWithHero(widgets: List<SidePanelWidget>, cols: Int): Packed =
    if (widgets.isEmpty()) Packed(null, emptyList())
    else Packed(Placed(widgets[0], 0, 0, cols, 1), pack(widgets.drop(1), cols))
```

**Step 1.4 运行确认通过**（同 1.2 命令）→ PASS，且旧 5 测试仍 PASS。

---

### Task 2: `SidePanelModel.move()`（TDD，纯逻辑；暂不删 tapInEdit）

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelModel.kt`
- Test: `common/src/test/java/com/shilapi/xcertplay/panel/SidePanelModelTest.kt`（新建）

**Step 2.1 写失败测试**：
```kotlin
package com.shilapi.xcertplay.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class SidePanelModelTest {
    private fun model(kinds: List<String>) =
        SidePanelModel(kinds.map { SidePanelWidget(it, TileSize.SMALL) })

    @test
    fun moveToFrontBecomesHero() {
        val m = model(listOf("clock", "battery", "tyres"))
        m.move(2, 0)
        assertEquals(listOf("tyres", "clock", "battery"), m.widgets.map { it.kind })
    }

    @Test
    fun moveAdjacentForward() {
        val m = model(listOf("clock", "battery", "tyres"))
        m.move(0, 1)
        assertEquals(listOf("battery", "clock", "tyres"), m.widgets.map { it.kind })
    }

    @Test
    fun moveSameIndexIsNoop() {
        val m = model(listOf("clock", "battery"))
        m.move(1, 1)
        assertEquals(listOf("clock", "battery"), m.widgets.map { it.kind })
    }

    @Test
    fun moveOutOfBoundsClamps() {
        val m = model(listOf("clock", "battery"))
        m.move(0, 99)
        assertEquals(listOf("battery", "clock"), m.widgets.map { it.kind })
    }
}
```
（注意第一个方法注解写 `@Test`；context 为 null 时 persist 自动跳过，纯 JVM 可跑。）

**Step 2.2 运行确认失败**：同上 `--tests "com.shilapi.xcertplay.panel.SidePanelModelTest"` → 编译错误（move 不存在）。

**Step 2.3 实现**（SidePanelModel.kt 追加，与 tapInEdit 并存）：
```kotlin
/** Move the widget at [from] to [to] in queue order (index 0 is the hero) and persist. */
fun move(from: Int, to: Int) {
    if (from == to || from !in widgets.indices) return
    val moved = widgets.removeAt(from)
    widgets.add(to.coerceIn(0, widgets.size), moved)
    persist()
}
```

**Step 2.4 运行确认通过** → 4 个新测试 PASS。

---

### Task 3: 编辑器拆文件（纯搬迁，行为不变）

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelEditor.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelCompose.kt`

**Step 3.1** 把 `SidePanelEditor` 与 `AddDrawer` 两个 composable（连同其依赖的 `sizeLabel`）从 SidePanelCompose.kt 原样搬到新文件 SidePanelEditor.kt（private 可见性提升为文件内 private 不变，跨文件引用去掉）。所需 import 照搬。

**Step 3.2** 编译：`./gradlew :common:compileDebugKotlin --console=plain` → OK。

**Step 3.3** 跑面板测试（Task1/2 的）→ 仍 PASS。

---

### Task 4: Hero+紧凑渲染 + 删 GroupCaption + 编辑态重接线 + 文案

**Files:**
- Modify: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelCompose.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelEditor.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelModel.kt`（删 `tapInEdit`、`selected`）
- Modify: `common/src/main/res/values{,-ar,-es,-ja,-ru,-uk,-zh-rCN,-zh-rTW}/panel_tiles.xml`（改写 `panel_edit_hint`）

**Step 4.1 常量**（SidePanelCompose.kt 顶部）：
```kotlin
private const val PANEL_ROW_H_DP = 60      // compact row height
private const val PANEL_TALL_H_DP = 126    // rowSpan 2: 60*2 + 6 gap
private const val PANEL_HERO_H_DP = 118
private const val PANEL_GAP_DP = 6
```

**Step 4.2 布局编排**（`SidePanelPanel`）：
- `BoxWithConstraints` 内改用 `val packed = remember(...) { SidePanelLayout.packWithHero(model.widgets.toList(), columns) }`
- `SidePanelGrid`/`SidePanelEditor` 签名改为接收 `packed: SidePanelLayout.Packed`
- `verticalScroll` 的 `ScrollState` 提升到 `SidePanelPanel` 记忆并下传（拖拽自动滚要用）
- 删除 `GroupCaption` composable 与 grid 内 `captioned` 逻辑

**Step 4.3 Hero 渲染**（新 composable，放置在 grid 顶部）：
- 全宽、`PANEL_HERO_H_DP` 高、`RoundedCornerShape(22.dp)`，背景 `palette.tileEdit`，边框 1.5dp `palette.accent`
- 内容：10sp 标签行（secondary）+ 36sp 主数值（复用 `valueWithUnit`，unit 13sp）+ 状态色条逻辑与现 TileContent 相同（accent/stale/strip 保留）+ `readout.fraction` 存在时画进度弧（复用现有 fraction bar 样式）
- 无 fraction 的量（车速/时钟）不画弧

**Step 4.4 紧凑块渲染**（替换现 `TileContent` 的 VALUE 分支）：
- 单行 `Row`：`label`（10sp secondary，weight 1f）+ `valueWithUnit(...)`（17sp，weight 2f，end 对齐，maxLines 1 Ellipsis）
- 状态色条保留（3dp 竖条）；stale 变灰保留
- `TileHeader` 删除（图标+标题头不再渲染）

**Step 4.5 MULTI/TYRES/SYSTEM 分支**：去掉 `TileHeader`，改为顶部一行 10sp 标签（`string(kind.titleRes)`），内容区不变；高度：rowSpan1 = 60dp（padding 降为 8dp），rowSpan2 = 126dp。

**Step 4.6 编辑态重接线**（SidePanelEditor.kt）：
- 删除 `model.selected` 引用与 `tapInEdit` 点击；tile 点击无操作（×/胶囊照常）
- 尺寸胶囊 `onClick = { model.cycleSize(kind.id) }`（原 longClick 循环一并删）
- 编辑态顶栏提示改用新文案（4.7）

**Step 4.7 删除模型旧接口**（SidePanelModel.kt）：删 `tapInEdit()` 与 `selected` 属性（startEditing/stopEditing 中对 selected 的赋值一并删）。

**Step 4.8 文案**（8 个 locale 的 `panel_edit_hint` 全部替换）：
| locale | 新文案 |
|---|---|
| values | Hold and drag a tile to reorder. Tap the size to change it, × to remove. |
| values-zh-rCN | 长按并拖动小组件排序，点尺寸改大小，点 × 移除。 |
| values-zh-rTW | 長按並拖動小組件排序，點尺寸改大小，點 × 移除。 |
| values-ja | 長押しでドラッグして並べ替え。サイズをタップで変更、×で削除します。 |
| values-es | Mantén pulsado y arrastra una tarjeta para reordenar. Toca el tamaño para cambiarlo, × para quitarla. |
| values-ru | Удерживайте и перетаскивайте плитку для изменения порядка. Коснитесь размера, чтобы изменить его, × — чтобы удалить. |
| values-uk | Утримуйте й перетягуйте плитку, щоб змінити порядок. Торкніться розміру, щоб змінити його, × — щоб видалити. |
| values-ar | اضغط مطولاً واسحب البطاقة لإعادة الترتيب. انقر الحجم لتغييره، × لإزالتها. |

**Step 4.9** 编译 + 面板测试：`./gradlew :common:compileDebugKotlin :common:testDebugUnitTest --tests "com.shilapi.xcertplay.panel.*" --console=plain` → PASS。

---

### Task 5: 长按拖拽（SidePanelDrag.kt）

**Files:**
- Create: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelDrag.kt`
- Modify: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelCompose.kt`（grid 编辑态 tile 接手势、落点坑叠加层）
- Modify: `common/src/main/java/com/shilapi/xcertplay/panel/SidePanelEditor.kt`（编辑态 tile 同样接手势）

**Step 5.1 DragState**（SidePanelDrag.kt）：
```kotlin
class DragState {
    var liftedKind by mutableStateOf<String?>(null)
        private set
    var targetIndex by mutableStateOf(-1)
        private set
    /** kind -> tile bounds in panel coordinates, refreshed each frame by onGloballyPositioned. */
    val bounds = mutableStateMapOf<String, Rect>()
    var fingerY by mutableStateOf(0f)
        private set

    fun begin(kind: String) { liftedKind = kind; targetIndex = -1 }
    fun drag(amount: Offset, absoluteY: Float) { fingerY = absoluteY; /* Step 5.2 */ }
    fun cancel() { liftedKind = null; targetIndex = -1 }
}
```

**Step 5.2 落点索引**（drag() 内）：以 `bounds` 中各 tile 的中心 Y 排序（渲染顺序 == widgets 顺序），`targetIndex` = 第一个中心 Y > fingerY 的 tile 在 widgets 中的下标；都小则为末尾。`targetIndex == liftedIndex` 时回落 -1（不显示坑）。

**Step 5.3 手势接线**（编辑态与浏览态 tile 相同，包在 tile Box 上）：
```kotlin
.pointerInput(kind) {
    detectDragGesturesAfterLongPress(
        onDragStart = { if (widgets.size > 1) { model.startEditing(); dragState.begin(kind) } },
        onDrag = { change, amount ->
            change.consume()
            dragState.drag(amount, change.position.y)
            // 边缘自动滚：dragState.fingerY < 96 → scrollState.scrollBy(-8f)；> height-96 → +8f
        },
        onDragEnd = { dragState.liftedKind?.let { lift -> model.move(model.widgets.indexOfFirst { it.kind == lift }, dragState.targetIndex) }; dragState.cancel() },
        onDragCancel = { dragState.cancel() },
    )
}
```
- 长按前位移 >12dp：`detectDragGesturesAfterLongPress` 原生语义已处理（长按前拖动=取消），无需额外代码
- tile 的 `onGloballyPositioned { dragState.bounds[kind] = it.boundsInParent() }` 刷新边界

**Step 5.4 落点坑叠加**（`SidePanelPanel` 最外层 Box，拖起时绘制）：按 `bounds` 中第 `targetIndex` 个 kind 的矩形画 `dashed border`（accent 1.5dp、圆角 22dp、accent 6% 填充）；被抬起 tile 本体加 `graphicsLayer(scaleX=1.06, scaleY=1.06, shadowElevation=…)` + `offset(dragOffset)` 跟手；其余照常。拖动中 grid **不重排**，松手 move 才重排。

**Step 5.5** 队列 ≤1：`onDragStart` 内 `if (widgets.size > 1)` 守卫已保证不抬起。
**Step 5.6** 面板重建（转屏/分屏）：`SidePanelPanel` 重组时 `remember { DragState() }` 随之重建 → 抬起态自然清空，与 A2 逻辑一致。

**Step 5.7** 编译 + 全面板测试 → PASS。

---

### Task 6: 文档 + 全套 CI + 打包

**Files:**
- Modify: `docs/SIDE_PANEL.md`（"Widgets" 一段：删 tap-two-to-swap 描述，改为 hero+紧凑块+长按拖拽描述，密度数字）
- Modify: `docs/plans/2026-10-16-side-panel-rework.md`（如实现与计划有偏差，回填）

**Step 6.1** 更新 SIDE_PANEL.md 的 Widgets 段与编辑器描述。
**Step 6.2** 全套 CI 命令（见头部约束）→ BUILD SUCCESSFUL（已知 10 个环境性先期失败允许存在，数量不得增加）。
**Step 6.3** 产出测试包：`./gradlew :mobile:assembleDebug --console=plain`，产物 `mobile/build/outputs/apk/debug/mobile-debug.apk` 交付用户真车验证。

**真车验收清单（交付时附给用户）：**
1. 同屏可见块数 ≥8（原约 5）
2. 队列第一块为 Hero（全宽大数字；电量类有进度弧，车速类无）
3. 无"行驶"类分组小标题
4. 长按拖拽：抬起反馈、虚线落点坑、松手落位、首格=Hero、拖动中数据刷新不丢抬起态
5. 旧布局存档打开后首块自动成为 Hero，其余顺序不变
6. 编辑态：× 删除、尺寸胶囊点按循环、+ 抽屉、完成；无点两下交换
7. 深/浅色主题、阿拉伯语 RTL 抽查

---

### 执行注意
- 每 Task 结束必须 `:common:testDebugUnitTest --tests "com.shilapi.xcertplay.panel.*"` 绿再进下一 Task
- Compose 无 UI 测试框架：视觉正确性依赖编译+单测+真车清单，不在本计划引入新测试依赖
- 不执行任何 git commit；`.superpowers/` 会话目录已在 Task 0 加入 .gitignore
