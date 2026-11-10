# Reveal

手机上的抠图 App（Android），实验性项目。所有处理都在本机完成，图片不上传。

## 现在能做什么

- 抠图：按颜色范围、色带、魔棒、画笔修补，配范围蒙版与蒙版细化
- 图层：组、蒙版、剪贴蒙版、混合模式、不透明度
- 选区：矩形 / 椭圆 / 套索 / 魔棒 / 按颜色范围
- 调色 11 项、滤镜 30+，拖动参数实时预览
- 画笔：画笔 / 橡皮 / 涂抹 / 减淡 / 加深 / 海绵 / 图章 / 修复 / 文字
- 打开与导出：PNG / JPG / WebP，以及分层 `.psd` 文件
- 撤销 / 重做、工程自动保存

## 环境要求

- Android Studio（Rabbit 2 或更新）
- JDK 17（Android Studio 自带）
- 可选：Git、GitHub 账号（用来发版）

## 第一次打开

1. Android Studio → `File` → `Open` → 选到这个 `palette` 目录
2. 等右下角 Gradle Sync 跑完（第一次要下载依赖，需要几分钟）
3. 连手机（开 USB 调试）或开模拟器，点绿色三角运行

Gradle 下载慢的话，把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成
`https\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`。

## 目录结构

```
palette/
├── app/                         # 主模块
│   ├── build.gradle.kts         # 模块构建脚本
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/fonts/        # 16 款随包字体（OFL，许可证原文同目录）
│       ├── java/com/reveal/editor/
│       │   ├── MainActivity.kt
│       │   ├── data/            # 工程存储、文件接收、诊断日志
│       │   ├── image/           # 分层文件读写、图像处理、选区
│       │   └── ui/              # Compose 界面 + 自绘画布
│       └── res/                 # 字符串、主题、图标
├── docs/                        # 修复记录、测试案例、第三方许可
├── gradle/libs.versions.toml    # 依赖版本集中管理（版本目录）
├── .github/workflows/android.yml# CI：推代码自动出 APK，打 tag 自动发版
├── keystore.properties.example  # 签名配置模板
└── LICENSE
```

## 生成签名密钥（第一次发版前做一次）

```bash
keytool -genkeypair -v -keystore reveal-release.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias reveal \
  -storepass 你的口令 -keypass 你的口令 \
  -dname "CN=Reveal, OU=Dev, O=Reveal, L=City, ST=Province, C=CN"
```

然后把 `keystore.properties.example` 复制成 `keystore.properties`，填上文件名和口令。
这两个文件都在 `.gitignore` 里，不会被上传。

## 发版流程

1. 代码推到 GitHub 的 `main` 分支 → Actions 自动编译 debug APK，在仓库 `Actions` 页面可下载
2. 打 tag 并发版：`git tag v0.74.93 && git push origin v0.74.93`
3. 仓库 `Releases` 页面会自动挂出正式 APK，任何人不用登录就能下载

CI 里正式签名需要四个 Secrets：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
`KEYSTORE_BASE64` 用下面命令生成：

```bash
base64 -w 0 reveal-release.jks
```

没配 Secrets 也能编译，只是产出的是 debug 签名，适合自己测试。

## 技术选型

| 部分 | 选择 | 原因 |
| --- | --- | --- |
| UI | Jetpack Compose | 现代、代码短、好维护 |
| 画布 | 自绘 Canvas + 位图合成 | 图层实时合成，放大看像素不失真 |
| 图像处理 | 逐像素算法 + 3D LUT | 调色 / 滤镜实时预览 |
| 构建 | Gradle Kotlin DSL + 版本目录 | 依赖版本集中，主流工程做法 |
| 签名 | keystore + secrets | 本地能编，CI 也能签，别人 clone 也能跑 |

## 已知限制

- 工作分辨率上限 4096 长边（手机内存限制）
- 超大分层文档（几百 MB / 几万像素）不支持
- 外链 / TIFF 置入的智能对象按平面层读入
- WebP 无损导出需要 Android 11 及以上

## 许可

本项目采用 **PolyForm Noncommercial License 1.0.0** 授权：源码公开，个人非商业用途免费。

- 个人学习、研究、实验、自用、爱好者项目：可以自由使用、修改、分发
- 商业用途（售卖、付费分发、公司生产经营等）：未经授权不得使用
- 这不是 OSI 认定的开源许可证，属于「源码可见（source-available）」

许可证全文见 [LICENSE](LICENSE)。简单说：你可以随便拿去自己用、学着改，但不能拿去卖钱。

GitHub 的许可证识别列表里没有 PolyForm，仓库页会显示成 “Other”，这不是没写许可证——名字和全文链接见上。

**使用风险**：软件按现状提供，不保证不出错；编辑会覆盖原文件，重要图片请自己先备份；分层文件的兼容性不保证和任何商业软件完全一致。

另外：随包的 16 款字体按 SIL OFL 1.1 单独授权，不受本项目许可限制（别人可以单独把字体用于商业项目）；界面图标来自 Material Icons（Apache-2.0）。

项目名称与图标不随代码授权，仅用于标识本项目本体。

第三方组件（Kotlin、AndroidX、Jetpack Compose，Apache License 2.0）与随包 16 款字体（SIL Open Font License 1.1）的许可证原文和说明见 `docs/第三方许可.md`。
