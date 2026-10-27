# 逐文件来源与处理矩阵

[返回首页](../README.md)

来源矩阵以原有 14 个 Java 的本地蓝境工程为基线，3.3.2/code12 新增 `BuiltinWallpaperActivity.java`，3.3.3/code13 又新增 `VerifiedLaunchPolicy.java`，当前共 16 Java。已沿本地早期版本和 CleanYourTV 当前可读单文件做对照，未确认实质相同实现片段；这不是「全项目原创」证明。新入口、内置资源和模式恢复改动分别记录，不将所有文件描述为未修改。

本地早期版本 3.3/code10 与 3.3.1/code11 基线包名一致，说明本地版本流转。候选上游为 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)，其 MIT 声明已保留。上游当前包名、SDK、Gradle/AndroidX 与界面结构不同；可读比较范围有限，未完成全部历史比对。

下表“本地实现”指来源路径明确，不把它当作作者版权认定。我们有权授权的新增/改写部分按 [许可范围](../LICENSE-SCOPE.md) 采用 MIT；如后续确认上游复用，继续保留对应原声明。

| 文件 | 角色 | 上游/第三方对应情况 | 候选处理 |
| --- | --- | --- | --- |
| MainActivity.java | 自绘桌面、应用库、遥控、系统入口 | 与上游同类功能，但本地 HomeView 与其 RecyclerView/AppAdapter 结构不同 | 本轮增加内置背景入口；已通过正常界面选择照片并看到显示，完整入口回归另验 |
| BuiltinWallpaperActivity.java | 固定内置背景选择与恢复先前模式 | 3.3.2 本轮新增实现 | nonexported、固定选择；外部启动被系统拒绝，选择→恢复原模式→再次选择实机序列通过 |
| IconMapping.java | 公共应用包名与图标名称映射 | 未确认上游实现复用；公共包名不等于厂商源码 | 纳入，与图标资产分开 |
| IconRegistry.java | PNG 缓存、通用图标回退 | Lucide/Feather 义务取决于实际图标文件 | 纳入代码，不带旧 PNG |
| QiyiguoLaunchPolicy.java | 严格限定的启动参数策略 | 未确认上游对应实现 | 纳入；指定 TCL 版本一次双冷启动参数对照有效，安装后全入口回归未完成 |
| VerifiedLaunchPolicy.java | 芒果／酷喵指定版本的固定公开 Intent 与快速重复按键限制 | 3.3.3 本地新增，未复用第三方代码 | 仅本地测试；蓝境内实机启动与播放待验证，应用库保留普通启动 |
| OfflineGallery.java | 清单校验、路径限制、采样解码 | 读取图片的代码不取得图片分发权 | 纳入，配原创空图库 |
| OfflineRotationPolicy.java | 轮换周期与持久 shuffle bag | 未确认上游对应实现 | 纳入本地实现 |
| ScenicView.java | 照片采样、模糊和程序背景 | 没有包含图片像素 | 纳入；公开源码默认程序背景，选图与解码需分别验证 |
| WallpaperBootReceiver.java | 开机轮换状态与内部广播 | 运行时 UUID 不是固化私人设备标识 | 纳入本地实现 |
| WallpaperController.java | 图库/在线缓存、轮换、失败回退 | 服务客户端不代表图片授权 | 纳入；保留官方服务限定，本轮新增内置模式与先前模式恢复 |
| WallpaperPolicy.java | 公共主机白名单、尺寸采样、周期规则 | validKey 是输入格式检查，不含 key 值 | 纳入，白名单与官方服务限定同步 |
| WallpaperSettingsActivity.java | 旧本地背景配置与隐藏 key 输入 | 输入逻辑不是凭据；保留的旧实现 | 与本轮 BuiltinWallpaperActivity 区分，不将旧设置类宣传为已启用的通用导入入口 |
| WallpaperTls.java | 逐连接 TLS1.2 和证书验证 | 内存 KeyStore 是信任配置，不是用户签名密钥 | 纳入代码，不迁入旧证书资产 |
| WaveView.java | OpenGL ES2 程序背景 | Original 注释仅作为线索，非版权证明 | 纳入；当前主界面未启用 |
| WeatherView.java | 天气覆盖、缓存、刷新、说明 | 已有 Open-Meteo / CC BY4.0 数据署名 | 保留代码和署名，不带位置/种子/缓存 |
| AndroidManifest.xml | 应用、权限和入口声明 | 未发现第三方代码片段声明 | 3.3.3/code13；保持 API 与权限集合不变，背景入口仍 nonexported |
| res/values/strings.xml | 应用名 | 本次重新生成的名称资源 | 纳入原创资源 |
| res/drawable/icon.xml | 当前已安装版的实际 Android 应用图标 | 本次原创电视／地平线图形，Bluehome contributors MIT | 原路径与颜色保留；对应可复用 SVG 同源导出 |
| assets/bluehome-app-icon.svg | 蓝境应用图标的可复用矢量副本 | 由上述 XML 精确导出，原创 MIT；512 画布、48 viewBox | 首页使用的实际应用图标，不是另行设计的替代标识 |
| app/assets/offline-gallery.json | 空图库 | 本次原创空清单，不含作品 ID | 纳入 |
| app/assets/builtin/hidden-lake-cc0.jpg | 可选内置山湖照片 | Joalde，CC0 1.0；公开副本已经移除元数据 | 本轮唯一新增摄影资源；来源、尺寸和 hash 见 ASSETS 文档 |
| tools/build_unsigned.py | 固定白名单编译和 unsigned 打包 | 本次新写，使用标准 Android/JDK 工具 | 纳入，不迁入旧签名/安装脚本 |

完整 CleanYourTV MIT 文本与字体例外见 [第三方声明](../third_party/cleanyourtv-LICENSE.txt)。没有分发其 Outfit 字体，也没有将旧 Noto、Lucide/Feather、Wallhaven 或其他旧照片随源码迁入。本轮 Hidden Lake 单独适用 CC0，不适用代码 MIT。

## 3.3.2 本轮变更范围

新增固定内置照片的选择、深蓝默认背景和恢复先前背景模式。恢复的是模式，不承诺上一张精确图片。入口不接受任意 URL、路径、程序或可转发 Intent；安全要求见 [安全边界](SECURITY.zh-CN.md)。构建与设备更新的实际完成状态见 [构建记录](BUILD.zh-CN.md)，不能套用 3.3.1 的验证结论。

本轮 15 Java 的 unsigned 全链构建、独立 52 项主机断言与 5 项契约检查通过；公开 APK 随包 assets 仅内置 Hidden Lake 与空旧图库。私人设备覆盖安装、3.3.2/code12 版本回读及正常菜单选择 Hidden Lake→恢复原背景模式→再次选择实机序列已完成，真实截图已采集并审查。已安装 APK 与本轮签名包 hash 匹配，编译图标 XML 与公开 unsigned 包相同；背景入口外部启动被系统拒绝。上述记录不证明全部源码原创，也不等同真机故障注入或全入口回归；模式恢复也不保证上一张精确图片。
