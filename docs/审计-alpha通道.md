# 0.74.87 附：alpha 通道静态审计（把所有调色路径翻了一遍）

结论：**没有任何调色算法会主动把 alpha 写小**。逐条证据（文件:行）：

| 位置 | 写像素的方式 | 是否保 alpha |
|---|---|---|
| `ColorFx.map()`（照片滤镜等旧路径的公共函数） | `(a shl 24) or (n and 0xFFFFFF)`，`a` 取原像素 | ✅ |
| `Lut3D.apply()` | `(a shl 24) or rOut/gOut/bOut`，`a` 取原像素 | ✅ |
| `HueSat.apply()` / `applyMatrix()` | 逐像素 `(a shl 24)` 或 `src.copy(...)` 上画 | ✅ |
| `ColorDocks.kt` 两处逐像素滤镜 | `px[i] = (a shl 24) or ...` | ✅ |
| `compositeWithMask()` | `out.alpha = base.alpha`（`allowAlpha=false` 时） | ✅ |
| `FilterFx.bandCut` | 唯一「故意改 alpha」的滤镜，只在色带抠图里用 | 预期内 |

所以「圈外变透明」如果还在出现，只剩三个可能：
1. **图层像素已经被早先版本写坏**（撤不回来的那种）——请用原图重开验证；
2. **绘制层**（预览叠加）还有遗漏的分支；
3. 走的不是调色路径，而是「到新层 / 橡皮 / 背景橡皮」这类**本来就改 alpha 的工具**。

配合 0.74.87 的 alpha 自检日志：只要「应用」之后日志没有 `alpha 警告`，第 1、3 条就被排除，问题必然在绘制层，我就直接去把绘制分支整段重写。

（本轮没有代码改动之外的产物，APK 仍是 0.74.87。）