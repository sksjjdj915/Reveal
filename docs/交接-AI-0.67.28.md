# Reveal（palette）交接文档 — 给下一个 AI

更新：2026-11-08　当前版本 **0.67.28**（versionCode 293）

---

## 1. 这是什么

安卓手机上的修图 App，用户的核心诉求是**给会用桌面修图软件的人应急**，所以界面与功能一律照桌面软件的习惯做（分组、命名、行为），他觉得「像不像」就是验收标准。

- 代码：`E:\deepseek\palette`
- 包名：`com.reveal.editor`
- 技术栈：Kotlin + Jetpack Compose，全部自绘 Canvas，没有用第三方图像库
- 桌面产物：`%USERPROFILE%\Desktop\Reveal-0.67.28-debug.apk`（**桌面只留最新一个**，旧的必须删）

## 2. 关键文件（都在 `app\src\main\java\com\reveal\editor\`）

| 文件 | 职责 |
|---|---|
| `ui\SketchView.kt` | 心脏，九千多行：画布、手绘、选区、所有工具的落笔逻辑、撤销栈 |
| `ui\ToolFx.kt` | 纯像素算法（污点修复、红眼、海绵、艺术历史、颜色替换、背景橡皮…） |
| `image\Selection.kt` | 选区掩码：布尔运算、魔棒、羽化、边界 |
| `ui\EditorScreen.kt` | 顶层布局、左侧 rail、底部参数条（optionsStrip）、各面板开关 |
| `ui\Panels.kt` | 通用组件：`ToolOptionsBar`、`ThinSlider`、`BarSlider` |
| `ui\ToolOptions.kt` | 一部分工具的参数条（`PsToolOptionsHost` 调） |
| `ui\SelectionTools.kt` / `ui\SelectMaskPanel.kt` | 选区参数条 / 选择并遮住面板 |
| `ui\PsExtraMenus.kt`、`ui\ColorFxTools.kt`、`ui\Dialogs.kt` | 菜单、调色工具、对话框 |

## 3. 出包配方（照抄，workdir = `E:\deepseek\palette`）

```powershell
$env:JAVA_HOME='%USERPROFILE%\.jdks\jbr-21.0.11'
$env:ANDROID_HOME='%USERPROFILE%\AppData\Local\Android\Sdk'
$env:GRADLE_USER_HOME='E:\deepseek\palette\.gradle-home'
node E:\deepseek\_bump2.cjs <新code> <新版本号>          # 改 versionCode / versionName
Remove-Item app\build\outputs\apk\debug\app-debug.apk   # 防拿到旧包
.\gradlew.bat assembleDebug --console=plain --no-daemon --no-build-cache --no-watch-fs "-Pkotlin.incremental=false"
& '%USERPROFILE%\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt2.exe' dump badging app\build\outputs\apk\debug\app-debug.apk | Select-String versionName
Copy-Item app\build\outputs\apk\debug\app-debug.apk "%USERPROFILE%\Desktop\Reveal-<ver>-debug.apk"
```

注意：

- **PowerShell 5.1 会在 gradle 报 SDK 警告时假报失败**（exit 1 / 「FAILURE」）。判据只认日志里的 `BUILD SUCCESSFUL`。最稳写法：`... *> E:\deepseek\_build.log` 然后看文件尾。
- 偶尔 `BindException: Address already in use`，等 10 秒重试即可。
- 只编译验证用 `compileDebugKotlin`（约 1 分钟），比整包快。
- APK 体积有时和上一版一模一样，别拿体积当判据，要解压 dex 搜新符号确认。

## 4. 改代码的方式

用 python / node 写补丁脚本，**全部 anchor 命中才写盘**（天然原子，改一半不会写坏）。原则：

- 改 `.kt` 用 `readFileSync` → `replace` → `writeFileSync(..., 'utf8')`，换行保持 `\n`。
- 一次一个脚本，跑完立刻 `compileDebugKotlin`。
- 同一脚本别重复跑（会插两遍）。
- PowerShell 5.1 不支持 heredoc，脚本一律落文件再跑。

## 5. 现在的状态

- 0.67.28 已出包，等用户按 `docs\要测清单-0.67.28.md` 验 6 项 + 上一版遗留 2 项。
- 这一版修了：加深（目标明度 0.22，保色相）、颜色替换（陡覆盖度曲线，去描边）、背景橡皮（下笔前 alpha + 最大覆盖度，去珠子；容差软过渡，去刀切边）。
- 前面积累的修复见 `docs\修复记录-*.md`（0.67.13 起每版一份）。
- 用户手上的对照文本：`%USERPROFILE%\Desktop\Reveal-测试案例.txt`（109 条测试案例）——**绝对不能改它**，他要拿原文逐条对照。

## 6. 必须知道的坑（都真实踩过）

1. **`dropMips()`**：任何改图层像素的地方（`replaceLayerAt`/`replaceActiveLayer`/撤销恢复/工具落笔）之后都要调，否则缩小看是旧图（「调色应用了但画面没变」的真因）。
2. **撤销记录 `Patch.after` 是 `PatchData`，提交即定型**（大图会 deflate 压缩）。想改最终结果必须在 `commitBrushStroke` 里 `IntArray` 之前做完。
3. **位图别名会崩**：把预览基准图直接赋给 `selection` 后，任何 `selection.recycle()` 都会回收它 → 下次预览 `getPixels on recycled bitmap`。复制一份再赋，`recycle` 前判 `!isRecycled`。
4. **别在 onDraw 里算重活**：`maskPercent` 那种整幅 `getPixels` 每帧调用 = 每帧几十 MB（缩放卡顿的真因）。判空用分块早退（`maskHasContent`），派生位图（描边/压暗）丢后台线程。
5. **一笔之内按「最大覆盖度」算，不要累乘/累减**：竹节、深点、月牙、越擦越透全是这个原因。
6. **颜色混合别用线性 `c/255`**：笔尖软边会留一圈中间色，看着像描边。
7. **参数条有两套**：`EditorScreen.kt` 的 optionsStrip（画笔类、仿制图章/修复画笔、减淡加深、涂抹）和 `ToolOptions.kt`（经 `PsToolOptionsHost` 的其余工具）。改错地方等于没改——改前先确认这个工具走哪一套。
8. **「没有某参数」多半是被做成了循环按钮**；要 3 个以上滑块得用 `ToolOptionsBar(extraSliders = listOf(BarSlider(...)))`。
9. **Compose 里读普通 `var` 不会重组**：开关点不变蓝就是这个原因，要 `mutableStateOf` / `mutableIntStateOf`。
10. **面板默认值必须是中性的**（全 0 / 不改变选区），否则一打开就改了用户的东西。
11. **选择并遮住的预览必须基于基准图一次算完**，不能就地叠加（越点越大）。
12. **选区当蒙版**：`DST_IN` 看的是 alpha，先铺不透明黑再把选区画上去等于没有蒙版。

## 7. 用户在意的沟通方式

- **大白话、短**。先结论 + 他要做什么，别铺表格讲原理。
- 每次交付必须分两段：**做了什么 / 没做什么**。不许把「部分完成」说成完成；做不到的要写清是什么限制、什么代价。他明确说过「做完啊不要每次都留一点」。
- 他报 bug 是一口气说五六条（不编号），要**逐条对号入座**、按同一顺序回「已修/没修」。
- 他说的「界面不一样我是不会通过的」——界面对齐桌面端是硬标准。
- 不要用模拟器验收，他自己在真机上试。
- 长任务中途要报进度（测到几项、还剩多少）。

## 8. 下一步可以做的

- 等他 0.67.28 的验收反馈，按条改。
- 已知仍与桌面端有差距（他没催，先记着）：污点修复没有真正的 PatchMatch 全图传播；修复画笔在带选区/蒙版/阿尔法锁定时不走泊松求解；结构大的补图会显得软。
