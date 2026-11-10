# Reveal 交接说明（v0.6.0 → v0.7.3）

> 给接手继续开发的 AI / 人看的。写于 2026-11-02。
> 工程根目录：`E:\deepseek\palette`（Android / Jetpack Compose 绘画 App）

## 一、现在是什么状态

- 基线代码是 v0.6.0，本次在它之上做了 8 个提交，一路做到 **v0.7.3（versionCode 15）**。
- 全部改动都已编译通过、打包成 APK 发给用户装过机，最后一版用户确认「这版对了」。
- git 起点提交：`50b1c51 baseline: Reveal v0.6.0`；当前 HEAD：`202dd64`。

```
202dd64 feat: 调色区改为独立导入式调色页（进入空白/两个导入入口/逐层导入/等比预览/独立撤销）(v0.7.3)
2aaa17c fix: 设置对话框两行叠在一起，改成 Column 可滚动 (v0.7.2)
7428054 feat: 笔刷变尖/抖动/散开/圆度，多边形改成钢笔样式 (v0.7.1)
32cbc9a feat: 四种笔刷+参数、多边形描边加粗、整屏调色页 (v0.7.0)
41e48cd selection: 画布边缘也算选区边界，反选后外圈有蚂蚁线
2dfe9ff selection: 修好蚂蚁线绘制、选区操作可撤销
b5413e3 selection: 桌面端风格选区外压暗 + 动态蚂蚁线
626771e v0.6.1: desktop-style marching ants selection + long-press selection menu (fill/delete/duplicate)
50b1c51 baseline: Reveal v0.6.0
```

改动规模：7 个文件，+1405 / -18 行。

| 文件 | 变化 | 说明 |
| --- | --- | --- |
| `app/src/main/java/com/reveal/editor/ui/SketchView.kt` | +619 | 蚂蚁线、选区压暗、选区撤销、四种笔刷、钢笔样式、多边形可视化、mergedBitmap/layerBitmaps |
| `app/src/main/java/com/reveal/editor/ui/EditorScreen.kt` | +257 | 选区长按菜单、笔刷参数面板、调色入口、设置对话框修复 |
| `app/src/main/java/com/reveal/editor/ui/ColorAdjustScreen.kt` | +356 | 全新：独立调色页 |
| `app/src/main/java/com/reveal/editor/image/ColorAdjust.kt` | +129 | 全新：颜色矩阵与预览缩放 |
| `app/src/main/java/com/reveal/editor/image/Selection.kt` | +2 | 边缘检测补画布外框 |
| `app/build.gradle.kts` | ±4 | versionCode 7→15，versionName 0.6.0→0.7.3 |
| `docs/修复记录.md` | +56 | v0.6.1 / v0.7.0 / v0.7.3 三段记录 |

## 二、做了哪些功能（按版本）

### v0.6.1 选区改桌面端风格 + 长按菜单

- 选区从「蓝色填充 + 白线」改成桌面端那种动态蚂蚁线（黑白相间 8×8 棋盘，每 90ms 挪一格）。
- **任意工具**下只要有选区，长按屏幕 0.4 秒弹菜单：全选 / 反选 / 取消选择 / 填充前景色 / 删除选区内容 / 复制到新图层。
- 长按判定：位移超过 26dp 或当前没有选区就不弹；弹出后抬手要吞掉这次 UP，避免又画一笔。

### v0.6.2–0.6.4 选区观感与撤销

- 选区外压暗（桌面端那种）：整张铺 `Color.argb(150,0,0,0)`，再用选区 mask 以 `DST_OUT` 挖洞；蚂蚁线画在挖出的洞边上。
- 修蚂蚁线：`Paint` 上挂 `BitmapShader` 再 `drawBitmap` **不生效**，必须 `saveLayer` → 画白色 edge → `SRC_IN` 复涂棋盘图案。
- 选区操作进撤销栈：`Stroke` 增加 `selBefore/selAfter`（只存 alpha，省 4 倍内存，最多保留 12 条选区快照）；`undo()/redo()` 先回放选区再回放像素。
- 反选后画布最外圈没蚂蚁线 → `edgeBitmap` 里补「x/y 到边界也算边界」。

### v0.7.0–0.7.1 笔刷与钢笔

- 四种笔型：圆头 / 铅笔 / 马克笔 / 喷枪。笔头是 `stampFor()` 现算的位图（按 `brushType|size|hardness|color` 缓存），沿落点按 `brushSpacing` 盖章。
- 参数：大小、不透明度、硬度、流量、间距、**变尖**（`brushTaper`，抬手后按整笔弧长重画，两端收缩）、**抖动**（`brushJitter`）、**散开**（`brushScatter`）、**圆度**（`brushRoundness`，压扁笔头）。
- 多边形选区改成钢笔样子：亮蓝 `#29B6F6` 连线（5dp 半透明黑垫底 + 2dp 亮蓝）、绿色 `#3DDC84` 十字锚点、起点黄色 `#FFD54F`、当前点在画蓝环。

### v0.7.2 设置对话框修复

- `AlertDialog` 的 `text = { }` 只是 slot 不是容器，两段内容会重叠 → 包一层 `Column(Modifier.verticalScroll(...))`。

### v0.7.3 独立调色页（现在的形态）

- 从设置进「调色」，进入是**空白**，顶部两个入口：「导入相册」「从作画区导入」。
- 从作画区导入时问一句：**合并成一张** / **逐层导入（每个可见图层各一层）**；作画区本身不变。
- 预览等比显示、可双指缩放拖动；参数历史独立撤销/重做/重置。
- 四个折叠分组：基础（亮度/对比度/饱和度/色相/色温/暗部提亮）、细化、滤镜、AI —— 后三组目前是「待开发」占位。
- 底部：写入作画区（合并 / 逐层）或取消。

## 三、关键实现位置（接手先看这几处）

`SketchView.kt`
- `antsPattern / antsPaint / antsFillPaint / antsMatrix / antsPhase` + `antsTicker`：蚂蚁线。
- `drawSelection()`：先画压暗层 `selectionDim`，再用 saveLayer 方式叠蚂蚁线。
- `buildDimMask(mask)`、`selection.edgeBitmap(mask, scale)`：压暗与边界。
- `Stroke`、`pushSelectionStroke`、`packAlpha/unpackAlpha`、`applySelectionSnapshot`：选区撤销。
- `stampFor()`、`stampLine()`、`stampAt()`、`restampTapered()`：笔刷引擎。
- `drawPolyLine()`、`polyVertex()`：钢笔样式。
- `mergedBitmap()`、`layerBitmaps()`、`snapshotActiveLayer()`：给调色页取图。

`ColorAdjustScreen.kt` / `ColorAdjust.kt`
- `ColorAdjustDialog(merged, layers, onCancel, onApplyMerged, onApplyLayers)`：签名。
- `ColorAdjust.matrix(params)`：色温 → 饱和度 → 亮度对比 → 色相 → 暗部提亮，用 `ColorMatrix.postConcat` 串起来。
- `ColorAdjust.scaledForPreview(src, 900)`：预览用缩图，避免大图卡顿。

## 四、编译 / 打包（照抄就能跑）

```powershell
$env:JAVA_HOME        = "%USERPROFILE%\.jdks\jbr-21.0.11"
$env:ANDROID_HOME     = "%USERPROFILE%\AppData\Local\Android\Sdk"
$env:GRADLE_USER_HOME = "E:\deepseek\palette\.gradle-home"
& "%USERPROFILE%\.gradle\wrapper\dists\gradle-8.11.1-bin\eac4u065zwes5phgltp5f9b9e\gradle-8.11.1\bin\gradle.bat" `
  -p E:\deepseek\palette --no-daemon --no-build-cache assembleDebug
```

产物：`E:\deepseek\palette\app\build\outputs\apk\debug\app-debug.apk`
发版习惯：复制到 `%USERPROFILE%\Desktop\Reveal-<版本>-debug.apk`，删掉桌面旧包。

## 五、踩过的坑（别再踩）

1. **`Paint` 挂 `BitmapShader` 后 `drawBitmap` 不生效** —— 要 `saveLayer` + 白色 edge + `SRC_IN` 复涂。
2. **边缘检测要单独处理画布边框**，否则选区贴边时外圈没蚂蚁线。
3. **PowerShell 改中文源码会写坏**：`Get-Content -Raw` / `Set-Content` 曾把 `EditorScreen.kt` 中文写乱导致一串 `Syntax error`。要么用 `edit` 工具，要么 `[IO.File]::ReadAllText/WriteAllText` + `New-Object UTF8Encoding($false)`；失败就用 `git checkout -- <file>` 重来。
4. **PowerShell 的 `cd` 不改进程工作目录**：`[IO.File]` 用相对路径会落到别的目录，脚本还照样打印 patched。一律用绝对路径，改完 `Select-String` 复查。
5. **Kotlin 一个函数只能有一个尾随 lambda**：`Section(...) { } { }` 编译不过，前一个要写成 `onToggle = { ... }`。
6. 手敲常量名容易错：`Paint.FILTER_BITMAP_FLAG` 曾被写成 `FILTER_BITMAP_BITMAP_FLAG`。改完一定编译。

## 六、下一步待做（用户已提，未实现）

1. **调色页预览比例跟图走**：现在预览区是固定高度、图片 `ContentScale.Fit` 塞进去，会留黑边。要求按图片宽高比铺满宽度，太高时整块等比缩到放得下。
2. **调色区加图层面板**：横向缩略图条，点切当前层、眼睛切显隐、「+」新增空白层（也可从相册导一张进来）；调色只作用当前选中层，应用时逐层写回作画区。
3. **调色区进度接进工程保存**：现在工程只存作画区，调色区不动。
4. 细化 / 滤镜 / AI 三组仍是占位，等具体需求。

## 七、给接手的建议

- 改之前先 `git log` + 读 `docs/修复记录.md`，功能演进和限制都记在那里。
- `SketchView.kt` 已经 2000 行上下，动笔刷/选区尽量抽函数，不要继续往里堆 UI 逻辑（UI 在 `EditorScreen.kt`）。
- 每次改完必须编译 + 装机验证，用户对「看着能用其实没生效」很敏感。
