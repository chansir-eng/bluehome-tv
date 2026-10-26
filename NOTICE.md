# 来源与第三方声明

蓝境源码候选由用户提供的本地工程整理而来；不声称所有 Java 均原创。本次只改变三处源码行为/引用：移除未核实的备用 API 服务、同步收紧 API 主机白名单、重命名可选背景文件引用；其余应用逻辑未主动改写。

## CleanYourTV：用户提供的上游候选

来源：[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)。已核实其适用代码采用 MIT，Copyright (c) 2026 Gathin；[完整上游声明](third_party/cleanyourtv-LICENSE.txt)随候选保留，以免遗漏可能复用部分的声明。

尚未确认本地逐文件继承关系。现有包名、SDK、构建与界面结构不同，不足以排除旧版本复用或重写；也不据此证明全部原创。上游 MainActivity 的公开 HTML 只读取到部分正文，完整 raw 获取受限，没有绕过。

若本地包含上游代码或实质性部分，必须继续保留其版权、MIT 许可及免责声明。这份声明不把第三方代码的版权重新归为蓝境，也不把应用全部资源套用 MIT。

## Open-Meteo 数据

`WeatherView.java` 已有 Open-Meteo 与 CC BY 4.0 数据署名，原样保留。天气数据许可与客户端 Java 代码许可分别处理。服务及数据来源见 [Open-Meteo](https://open-meteo.com/) 和 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。本候选没有打包天气数据、缓存、真实城市或坐标。

## 未随候选分发的第三方素材

旧 Noto 字体、Lucide/Feather 或品牌 PNG 图标、Wallhaven 壁纸、其他摄影背景、公共 CA 资产均未迁入。以后重新引入时应核对实际文件来源与各自许可，保留适用声明。CleanYourTV 的 Outfit 字体也未随本候选分发。

本次新写 SVG 品牌图、Android vector、应用名称资源、空图库、示例和 unsigned 构建脚本由本次整理生成，纳入我们有权授权的蓝境新增内容 MIT 范围，署名 Bluehome contributors。
