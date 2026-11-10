package com.reveal.editor.ui

import com.reveal.editor.image.FilterFx
import kotlin.math.roundToInt

/**
 * 「滤镜」菜单里新增的那些（桌面端滤镜库的常用项）。
 * 每个 id 对应一条 FxSpec，ColorFxTools 的 fxSpec 会在兜底分支里转发到这里，
 * 所以界面、应用、撤销全走同一套（applyFilterToActive）。
 */
internal fun filterSpec(id: String): FxSpec? {
    val r = { v: FloatArray, i: Int -> v[i].roundToInt() }
    return when (id) {
        "findEdges" -> FxSpec(
            "查找边缘",
            "把轮廓勾勒成黑线（3×3 Sobel 梯度）。原始效果本身没有参数；这里的「阈值」用来只保留强边缘：越大越干净",
            listOf(FxKnob("阈值", 0f, 100f, 0f)), 0,
        ) { v, _, bmp -> FilterFx.findEdges(bmp, r(v, 0)) }

        "emboss" -> FxSpec(
            "浮雕效果",
            "沿光照方向对亮度做方向导数（ahead − behind），叠到中灰 128：角度＝光从哪来、高度＝浮雕厚薄、数量＝对比强度",
            listOf(
                FxKnob("角度", -360f, 360f, 135f),
                FxKnob("高度 1~10", 1f, 10f, 3f),
                FxKnob("数量 1~500", 1f, 500f, 100f),
            ), 0,
        ) { v, _, bmp -> FilterFx.embossAt(bmp, r(v, 0), r(v, 1), r(v, 2)) }

        "motionBlur" -> FxSpec(
            "动感模糊", "沿一个方向拉出残影", listOf(FxKnob("距离", 1f, 60f, 12f), FxKnob("角度", -180f, 180f, 0f)), 0,
        ) { v, _, bmp -> FilterFx.motionBlur(bmp, r(v, 0), v[1]) }

        "radialBlur" -> FxSpec(
            "径向模糊",
            "缩放＝沿半径冲出去（放射）；旋转＝沿圆周转起来（环状）。品质只改采样数：草图 8 / 好 16 / 最好 32",
            listOf(
                FxKnob("数量 1~100", 1f, 100f, 12f),
                FxKnob("方法 0缩放·1旋转", 0f, 1f, 0f),
                FxKnob("品质 0草图·1好·2最好", 0f, 2f, 1f),
            ), 0,
        ) { v, _, bmp -> FilterFx.radialBlurAt(bmp, r(v, 0), r(v, 1) == 1, r(v, 2)) }

        "boxBlur" -> FxSpec(
            "方框模糊", "快速整体模糊，越加越糊", listOf(FxKnob("半径", 1f, 24f, 4f)), 0,
        ) { v, _, bmp -> FilterFx.boxBlur(bmp, r(v, 0)) }

        "surfaceBlur" -> FxSpec(
            "表面模糊", "糊掉平的地方、留住边缘，适合磨皮", listOf(FxKnob("半径", 1f, 8f, 4f), FxKnob("阈值", 2f, 80f, 20f)), 0,
        ) { v, _, bmp -> FilterFx.surfaceBlur(bmp, r(v, 0), r(v, 1)) }

        "smartSharpen" -> FxSpec(
            "智能锐化", "只锐化真边缘，平坦处不放大噪点，高光不会死白", listOf(FxKnob("数量", 0f, 300f, 140f), FxKnob("半径", 1f, 12f, 3f), FxKnob("减少杂色", 0f, 100f, 20f)), 0,
        ) { v, _, bmp -> FilterFx.smartSharpen(bmp, r(v, 0), r(v, 1), r(v, 2)) }

        "unsharp" -> FxSpec(
            "USM 锐化", "让边缘变清楚，数量大了会有白边", listOf(FxKnob("数量", 0f, 300f, 120f), FxKnob("半径", 1f, 12f, 2f), FxKnob("阈值", 0f, 60f, 0f)), 0,
        ) { v, _, bmp -> FilterFx.unsharpMask(bmp, r(v, 0), r(v, 1), r(v, 2)) }

        "addNoise" -> FxSpec(
            "添加杂色",
            "两种分布都给了：高斯（钟形，痕迹自然）/ 平均（等概率，颗粒更硬）；单色＝RGB 同偏移",
            listOf(
                FxKnob("数量", 1f, 120f, 24f),
                FxKnob("分布 0高斯·1平均", 0f, 1f, 0f),
                FxKnob("单色 0关·1开", 0f, 1f, 0f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.addNoise(bmp, r(v, 0), r(v, 2) == 1, 12345, r(v, 1))
        }

        "median" -> FxSpec(
            "中间值",
            "取邻域中间色（半径上限 30）。半径越大越平；半径 >6 时按步长抽样以保速度",
            listOf(FxKnob("半径", 1f, 30f, 2f)), 0,
        ) { v, _, bmp -> FilterFx.median(bmp, r(v, 0)) }

        "dust" -> FxSpec(
            "蒙尘与划痕",
            "只替换与中值差超过阈值的像素（灰尘、划痕）。阈值按 0~255 语义，越小修得越多",
            listOf(FxKnob("半径", 1f, 30f, 2f), FxKnob("阈值", 0f, 255f, 30f)), 0,
        ) { v, _, bmp -> FilterFx.dustScratches(bmp, r(v, 0), r(v, 1)) }

        "ripple" -> FxSpec(
            "波纹",
            "按正弦波推挤像素：大小=波长、数量=振幅；补了方向与相位",
            listOf(
                FxKnob("数量", 1f, 120f, 12f),
                FxKnob("大小", 4f, 400f, 40f),
                FxKnob("方向 0水平·1垂直·2两者", 0f, 2f, 2f),
                FxKnob("相位 %", 0f, 100f, 0f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.rippleAt(bmp, r(v, 0), r(v, 1), r(v, 2), r(v, 3))
        }

        "spherize" -> FxSpec(
            "球面化",
            "把画面顶成球面（负值凹进去）。标准做法有正常/水平/垂直三种模式；这里另外补了中心点与半径",
            listOf(
                FxKnob("数量", -100f, 100f, 50f),
                FxKnob("模式 0正常·1水平·2垂直", 0f, 2f, 0f),
                FxKnob("中心 X %", 0f, 100f, 50f),
                FxKnob("中心 Y %", 0f, 100f, 50f),
                FxKnob("半径 %", 5f, 200f, 100f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.spherizeAt(bmp, r(v, 0), r(v, 1), r(v, 2), r(v, 3), r(v, 4))
        }

        "twirl" -> FxSpec(
            "旋转扭曲",
            "越靠中心转得越多、到边界归零（常见的旋转扭曲效果）。补了中心点与半径，可以只拧局部",
            listOf(
                FxKnob("角度", -720f, 720f, 120f),
                FxKnob("中心 X %", 0f, 100f, 50f),
                FxKnob("中心 Y %", 0f, 100f, 50f),
                FxKnob("半径 %", 5f, 200f, 100f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.twirlAt(bmp, r(v, 0), r(v, 1), r(v, 2), r(v, 3))
        }

        "pinch" -> FxSpec(
            "挤压",
            "把画面往中心挤（负值往外鼓）。原始版本只有强度且固定在画面中心；这里补了中心点与半径，可以只压局部",
            listOf(
                FxKnob("强度", -100f, 100f, 50f),
                FxKnob("中心 X %", 0f, 100f, 50f),
                FxKnob("中心 Y %", 0f, 100f, 50f),
                FxKnob("半径 %", 5f, 200f, 100f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.pinchAt(bmp, r(v, 0), r(v, 1), r(v, 2), r(v, 3))
        }

        "polar" -> FxSpec(
            "极坐标",
            "在「平面 ↔ 极坐标」之间互换；补了中心点与半径，可以只卷一部分画面",
            listOf(
                FxKnob("0 平面→极坐标 / 1 极坐标→平面", 0f, 1f, 0f),
                FxKnob("中心 X %", 0f, 100f, 50f),
                FxKnob("中心 Y %", 0f, 100f, 50f),
                FxKnob("半径 %", 10f, 200f, 100f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.polarAt(bmp, r(v, 0) == 0, r(v, 1), r(v, 2), r(v, 3))
        }

        "crystallize" -> FxSpec(
            "晶格化", "把画面切成小晶格", listOf(FxKnob("格子", 2f, 60f, 10f)), 0,
        ) { v, _, bmp -> FilterFx.crystallize(bmp, r(v, 0)) }

        "pointillize" -> FxSpec(
            "点状化", "白底上撒彩色圆点（像点画）", listOf(FxKnob("格子", 3f, 60f, 10f)), 0,
        ) { v, _, bmp -> FilterFx.pointillize(bmp, r(v, 0)) }

        "clouds" -> FxSpec(
            "云彩", "叠一层分形云，浓度大了就看不清原图", listOf(FxKnob("浓度", 0f, 100f, 50f)), 0,
        ) { v, _, bmp -> FilterFx.clouds(bmp, r(v, 0), (System.nanoTime() and 0xFFFF).toInt()) }

        "lensFlare" -> FxSpec(
            "镜头光晕",
            "光斑（中央辉光）+ 光环 + 沿光轴排布的鬼影；色温与常见光晕插件那套分组一致",
            listOf(
                FxKnob("亮度", 0f, 100f, 60f),
                FxKnob("镜头 0变焦·1 35mm·2 105mm·3 电影", 0f, 3f, 0f),
                FxKnob("中心 X %", 0f, 100f, 50f),
                FxKnob("中心 Y %", 0f, 100f, 50f),
                FxKnob("光斑半径 %", 10f, 200f, 100f),
                FxKnob("鬼影强度", 0f, 100f, 60f),
                FxKnob("光环大小", 0f, 100f, 60f),
                FxKnob("色温 0中性·1暖·2冷", 0f, 2f, 1f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.lensFlareAt(
                bmp, r(v, 2), r(v, 3), r(v, 0), r(v, 1), r(v, 4), r(v, 5), r(v, 6), r(v, 7),
            )
        }.withPresets(
            // 参数顺序：亮度, 镜头, X, Y, 半径, 鬼影, 光环, 色温
            "标准太阳" to listOf(65f, 0f, 50f, 50f, 100f, 60f, 60f, 1f),
            "电影暖斑" to listOf(80f, 3f, 65f, 30f, 130f, 80f, 75f, 1f),
            "冷调科技" to listOf(55f, 2f, 35f, 40f, 90f, 40f, 30f, 2f),
            "单点星芒" to listOf(90f, 1f, 50f, 45f, 60f, 10f, 20f, 0f),
            "环形光晕" to listOf(60f, 0f, 50f, 50f, 120f, 30f, 100f, 0f),
            "低调微光" to listOf(25f, 2f, 70f, 25f, 70f, 25f, 40f, 1f),
            "横向拉丝" to listOf(70f, 3f, 50f, 35f, 160f, 95f, 85f, 1f),
            "柔焦暖阳" to listOf(50f, 1f, 55f, 60f, 200f, 20f, 50f, 1f),
        )

        "highPass" -> FxSpec(
            "高反差保留", "只留细节（灰色为中性），常配合叠加做锐化", listOf(FxKnob("半径", 1f, 24f, 4f)), 0,
        ) { v, _, bmp -> FilterFx.highPass(bmp, r(v, 0)) }

        "oil" -> FxSpec(
            "油画",
            "按亮度分箱取众数（Kuwahara 思路）；清洁度控制纯色化、光照按梯度补高光",
            listOf(
                FxKnob("画笔大小", 1f, 10f, 3f),
                FxKnob("硬毛刷细节", 4f, 48f, 20f),
                FxKnob("清洁度", 0f, 10f, 5f),
                FxKnob("光照角度", -180f, 180f, 135f),
                FxKnob("光照强度", 0f, 100f, 0f),
                FxKnob("画笔样式 0简·1轻·2湿·3干·4浓", 0f, 4f, 0f),
                FxKnob("光照闪耀", 0f, 100f, 0f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.oilAt(bmp, r(v, 0), r(v, 1), r(v, 2), r(v, 3), r(v, 4), r(v, 5), r(v, 6))
        }

        "lighting" -> FxSpec(
            "光照效果",
            "把纹理通道当高度场做 bump 贴图，再用 Blinn-Phong 打光：点光/全光/聚光、光泽＝高光锐度、金属质感＝高光带不带底色",
            listOf(
                FxKnob("类型 0点光·1全光·2聚光", 0f, 2f, 0f),
                FxKnob("强度", 0f, 100f, 50f),
                FxKnob("曝光 -100~100", -100f, 100f, 0f),
                FxKnob("光泽 -100~100", -100f, 100f, 30f),
                FxKnob("金属质感 -100~100", -100f, 100f, 0f),
                FxKnob("环境", 0f, 100f, 15f),
                FxKnob("方位角", -180f, 180f, 135f),
                FxKnob("仰角", 0f, 90f, 45f),
                FxKnob("纹理通道 0亮·1红·2绿·3蓝", 0f, 3f, 0f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.lightingAt(
                bmp, r(v, 0), r(v, 1), r(v, 2), r(v, 3),
                r(v, 4), r(v, 5), r(v, 6), r(v, 7), r(v, 8),
            )
        }.withPresets(
            // 参数顺序：类型, 强度, 曝光, 光泽, 金属, 环境, 方位角, 仰角, 通道
            "三点光" to listOf(0f, 55f, 6f, 40f, 12f, 18f, 135f, 45f, 0f),
            "柔光箱" to listOf(1f, 40f, 10f, 12f, 0f, 30f, 0f, 90f, 0f),
            "硬侧光" to listOf(0f, 80f, 0f, 82f, 10f, 8f, 170f, 20f, 0f),
            "逆光边缘" to listOf(0f, 72f, -4f, 66f, 20f, 6f, -55f, 15f, 0f),
            "蜡烛暖光" to listOf(0f, 45f, 12f, 22f, 0f, 10f, 90f, 10f, 0f),
            "日光强照" to listOf(1f, 76f, 18f, 30f, 0f, 26f, 0f, 90f, 0f),
            "顶光戏剧" to listOf(0f, 86f, -8f, 92f, 26f, 4f, -90f, 80f, 0f),
            "金属反射" to listOf(0f, 60f, 0f, 100f, 86f, 12f, 45f, 30f, 0f),
            "纸纹浮雕" to listOf(0f, 36f, 4f, 6f, 0f, 22f, 135f, 62f, 0f),
            "夜景蓝调" to listOf(0f, 34f, -16f, 26f, 0f, 20f, -120f, 24f, 0f),
        )

        "minimum" -> FxSpec(
            "最小值", "暗部吃掉亮部，画面变厚", listOf(FxKnob("半径", 1f, 6f, 1f)), 0,
        ) { v, _, bmp -> FilterFx.minimum(bmp, r(v, 0)) }

        "maximum" -> FxSpec(
            "最大值", "亮部扩张，画面变薄", listOf(FxKnob("半径", 1f, 6f, 1f)), 0,
        ) { v, _, bmp -> FilterFx.maximum(bmp, r(v, 0)) }

        "offset" -> FxSpec(
            "位移",
            "整幅平移到指定位置；没定义区域可选环绕（默认）、重复边缘像素、设置为透明",
            listOf(
                FxKnob("水平", -2000f, 2000f, 0f),
                FxKnob("垂直", -2000f, 2000f, 0f),
                FxKnob("未定义区域 0环绕·1重复边缘·2透明", 0f, 2f, 0f),
            ), 0,
        ) { v, _, bmp -> FilterFx.offsetAt(bmp, r(v, 0), r(v, 1), r(v, 2)) }

        "fragments" -> FxSpec(
            "碎片", "四方向错开叠影，像抖动重曝", listOf(FxKnob("强度", 1f, 30f, 6f)), 0,
        ) { v, _, bmp -> FilterFx.fragments(bmp, r(v, 0)) }

        "colorHalftone" -> FxSpec(
            "彩色半调",
            "CMYK 四个色版各自旋转网屏，网点面积与油墨量成正比；四个网角错开（默认 108/162/90/45）是为了避免撞网出龟纹",
            listOf(
                FxKnob("最大半径", 2f, 60f, 8f),
                FxKnob("网角 1（青）", 0f, 360f, 108f),
                FxKnob("网角 2（洋红）", 0f, 360f, 162f),
                FxKnob("网角 3（黄）", 0f, 360f, 90f),
                FxKnob("网角 4（黑）", 0f, 360f, 45f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.colorHalftone(bmp, r(v, 0), r(v, 1), r(v, 2), r(v, 3), r(v, 4))
        }

        "threshold" -> FxSpec(
            "阈值",
            "亮度高于阈值色阶＝纯白、否则纯黑，只剩一个参数（阈值色阶 1~255）",
            listOf(FxKnob("阈值色阶", 1f, 255f, 128f)), 0,
        ) { v, _, bmp -> FilterFx.thresholdAt(bmp, r(v, 0)) }

        "bwFilter" -> FxSpec(
            "黑白转换",
            "六条色相带各自加权：红/黄/绿/青/蓝/洋红越大，该色越亮。同亮度不同颜色能出不同灰阶，这是它和「去色」的区别",
            listOf(
                FxKnob("红", 0f, 100f, 40f),
                FxKnob("黄", 0f, 100f, 60f),
                FxKnob("绿", 0f, 100f, 40f),
                FxKnob("青", 0f, 100f, 60f),
                FxKnob("蓝", 0f, 100f, 20f),
                FxKnob("洋红", 0f, 100f, 80f),
            ), 0,
        ) { v, _, bmp -> FilterFx.blackWhiteAt(bmp, r(v, 0), r(v, 1), r(v, 2), r(v, 3), r(v, 4), r(v, 5)) }

        "mosaic" -> FxSpec(
            "马赛克",
            "每个格子取平均色。格子越大越糊；标准马赛克只有「单元格大小」，这里另外给了格子形状",
            listOf(
                FxKnob("单元格大小", 2f, 120f, 12f),
                FxKnob("形状 0方·1圆", 0f, 1f, 0f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.mosaicAt(bmp, r(v, 0), r(v, 1))
        }

        "gaussBlur" -> FxSpec(
            "高斯模糊", "均匀柔化的模糊", listOf(FxKnob("半径", 1f, 40f, 6f)), 0,
        ) { v, _, bmp -> FilterFx.gaussBlur(bmp, r(v, 0)) }

        "reduceNoise" -> FxSpec(
            "减少杂色",
            "亮度只做轻度选择性平滑，色度单独重压——彩色噪点主要在色度上，这是它和表面模糊的区别",
            listOf(
                FxKnob("强度", 0f, 100f, 40f),
                FxKnob("保留细节", 0f, 100f, 40f),
                FxKnob("减少彩色杂色", 0f, 100f, 60f),
                FxKnob("锐化细节", 0f, 100f, 0f),
            ), 0,
        ) { v, _, bmp -> FilterFx.reduceNoise(bmp, r(v, 0), r(v, 1), r(v, 2), r(v, 3)) }

        "cutout" -> FxSpec(
            "木刻",
            "亮度分档 + 边缘描线：简化度越大边缘越圆滑、碎边越少；逼真度越高边越细、越贴合原图",
            listOf(
                FxKnob("色阶数 2~8", 2f, 8f, 4f),
                FxKnob("边缘简化度 0~10", 0f, 10f, 2f),
                FxKnob("边缘逼真度 1~3", 1f, 3f, 3f),
            ), 0,
        ) { v, _, bmp -> FilterFx.cutoutAt(bmp, r(v, 0), r(v, 1), r(v, 2)) }

        "watercolor" -> FxSpec(
            "水彩",
            "平涂 + 边缘压暗 + 纸纹，三个参数对应水彩的「画笔细节 / 阴影强度 / 纹理」",
            listOf(
                FxKnob("画笔细节 1~14", 1f, 14f, 8f),
                FxKnob("阴影强度 0~10", 0f, 10f, 2f),
                FxKnob("纹理 0~3", 0f, 3f, 1f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.watercolorAt(bmp, r(v, 0), r(v, 1), r(v, 2))
        }

        "filmGrain" -> FxSpec(
            "胶片颗粒",
            "颗粒＝成团的两层噪声（不是逐像素白噪声）；高光区域越大，亮部保留的颗粒越多（真实胶片亮部更明显）",
            listOf(
                FxKnob("颗粒 0~20", 0f, 20f, 8f),
                FxKnob("高光区域 0~10", 0f, 10f, 4f),
                FxKnob("强度 0~10", 0f, 10f, 7f),
            ), 0,
        ) { v, _, bmp ->
            FilterFx.filmGrain(bmp, r(v, 0), r(v, 1), r(v, 2), 20261110)
        }

        "adaptiveWide" -> FxSpec(
            "自适应广角", "把超广角/鱼眼拍弯的直线拉直；强度越大拉得越狠，边缘会被裁掉一点", listOf(
                FxKnob("强度", 0f, 100f, 60f), FxKnob("旋转", -180f, 180f, 0f), FxKnob("缩放", 40f, 300f, 100f),
            ), 0,
        ) { v, _, bmp -> FilterFx.adaptiveWide(bmp, r(v, 0), r(v, 1), r(v, 2)) }
        "lensCorrection" -> FxSpec(
            "镜头校正",
            "移去扭曲 + 垂直/水平透视 + 角度；色差是镜头对不同波长折射率不同造成的紫边/绿边，用每通道径向缩放模拟；晕影＝数量+中点",
            listOf(
                FxKnob("扭曲", -100f, 100f, 0f),
                FxKnob("垂直透视", -100f, 100f, 0f),
                FxKnob("水平透视", -100f, 100f, 0f),
                FxKnob("角度", -45f, 45f, 0f),
                FxKnob("色差·红", -100f, 100f, 0f),
                FxKnob("色差·绿", -100f, 100f, 0f),
                FxKnob("色差·蓝", -100f, 100f, 0f),
                FxKnob("晕影数量", -100f, 100f, 0f),
                FxKnob("晕影中点", 0f, 100f, 50f),
            ),
            0,
        ) { v, _, bmp ->
            FilterFx.lensCorrectAt(bmp, v[0], v[1], v[2], v[7], v[8], v[3], v[4], v[5], v[6])
        }.withAuto {
            // 按 EXIF 等效焦距估一个「移去扭曲」值，其余保持当前值
            val d = com.reveal.editor.data.ExifInfo.autoDistort()
            listOf(d, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 50f)
        }
        else -> null
    }
}
