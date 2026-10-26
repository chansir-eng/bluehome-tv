# 来源与第三方声明

本源码从用户提供的本地蓝境工程整理。公开版本移除了未核实的备用壁纸服务、私人配置和授权不明素材，并提供中性资源回退。3.3.2 新增非导出的内置照片选择页、固定 CC0 图片及恢复原背景模式，修复异步切换与低内存失败处理。逐文件来源范围见 [来源矩阵](docs/SOURCE-MATRIX.zh-CN.md)。这些整理不构成“全部代码原创”的证明。

## CleanYourTV：用户提供的来源线索

候选上游为 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)。已核实其代码采用 MIT，Copyright (c) 2026 Gathin；本仓库保留[完整原声明](third_party/cleanyourtv-LICENSE.txt)，避免遗漏可能复用部分的署名。

尚未确认本地逐文件继承关系。当前包名、SDK 与界面结构不同，不能据此排除旧版本复用或证明全量原创。若以后确认具体复用，应补充对应记录，并保持原版权与许可。不能将第三方权利重新归属为 Bluehome contributors。

## Hidden Lake 照片

`app/assets/builtin/hidden-lake-cc0.jpg` 来自 Joalde 的 [Hidden Lake from far above 08-2024](https://commons.wikimedia.org/wiki/File:Hidden_Lake_from_far_above_08-2024.jpg)，作品页标注 Own work / [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)。发布副本移除元数据与附属缩略图，保留主图像素；校验与处理说明见 [素材记录](docs/ASSETS.zh-CN.md#hidden-lake-cc0)。代码 MIT 不替代照片自身的 CC0 声明。

## Open-Meteo 天气

`WeatherView.java` 保留 Open-Meteo 与 CC BY 4.0 说明。天气数据许可与客户端 Java 代码许可分别适用：[Open-Meteo](https://open-meteo.com/)、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。本仓库不含天气数据、缓存或真实位置配置。

## 未纳入公开仓库的旧素材

旧 Noto 字体、Lucide/Feather/品牌 PNG 图标、Wallhaven 壁纸、其他摄影背景和自带 CA 未迁入。CleanYourTV 的 Outfit 字体也未随本仓库分发。日后引入任何素材，应核对实际文件来源与许可，不能套用项目代码 MIT。

本次有权授权的新增／改写代码、原创 SVG 品牌图、Android vector、文档与构建工具采用 MIT，署名 Bluehome contributors，具体范围见 [LICENSE-SCOPE.md](LICENSE-SCOPE.md)。
