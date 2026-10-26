# 逐文件来源与处理矩阵

[返回首页](../README.md)

14 个 Java 均来自用户提供的本地蓝境工程。已沿本地早期版本和 CleanYourTV 当前可读单文件做对照，未确认实质相同实现片段；这不是“全项目原创”证明，也不构成把全部源码隔离的理由。

本地早期版本 3.3/code10 与当前 3.3.1/code11 包名一致，说明本地版本流转。候选上游为 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)，其 MIT 声明已保留。上游当前包名、SDK、Gradle/AndroidX 与界面结构不同；可读比较范围有限，未完成全部历史比对。

下表“本地实现”指来源路径明确，不把它当作作者版权认定。我们有权授权的新增/改写部分按 [许可范围](../LICENSE-SCOPE.md) 采用 MIT；如后续确认上游复用，继续保留对应原声明。

| 文件 | 角色 | 上游/第三方对应情况 | 候选处理 |
| --- | --- | --- | --- |
| MainActivity.java | 自绘桌面、应用库、遥控、系统入口 | 与上游同类功能，但本地 HomeView 与其 RecyclerView/AppAdapter 结构不同 | 纳入本地实现；未主动改写逻辑 |
| IconMapping.java | 公共应用包名与图标名称映射 | 未确认上游实现复用；公共包名不等于厂商源码 | 纳入，与图标资产分开 |
| IconRegistry.java | PNG 缓存、通用图标回退 | Lucide/Feather 义务取决于实际图标文件 | 纳入代码，不带旧 PNG |
| QiyiguoLaunchPolicy.java | 严格限定的启动参数策略 | 未确认上游对应实现 | 纳入，保留设备效果未确认说明 |
| OfflineGallery.java | 清单校验、路径限制、采样解码 | 读取图片的代码不取得图片分发权 | 纳入，配原创空图库 |
| OfflineRotationPolicy.java | 轮换周期与持久 shuffle bag | 未确认上游对应实现 | 纳入本地实现 |
| ScenicView.java | 照片采样、模糊和程序背景 | 没有包含图片像素 | 纳入，仅将可选图名改为 default-background.png |
| WallpaperBootReceiver.java | 开机轮换状态与内部广播 | 运行时 UUID 不是固化私人设备标识 | 纳入本地实现 |
| WallpaperController.java | 图库/在线缓存、轮换、失败回退 | 服务客户端不代表图片授权 | 纳入，移除未核实备用服务的请求回退 |
| WallpaperPolicy.java | 公共主机白名单、尺寸采样、周期规则 | validKey 是输入格式检查，不含 key 值 | 纳入，白名单与官方服务限定同步 |
| WallpaperSettingsActivity.java | 本地背景配置与隐藏 key 输入 | 输入逻辑不是凭据；当前 Manifest 未注册 | 保留源码，不宣传入口已可用 |
| WallpaperTls.java | 逐连接 TLS1.2 和证书验证 | 内存 KeyStore 是信任配置，不是用户签名密钥 | 纳入代码，不迁入旧证书资产 |
| WaveView.java | OpenGL ES2 程序背景 | Original 注释仅作为线索，非版权证明 | 纳入；当前主界面未启用 |
| WeatherView.java | 天气覆盖、缓存、刷新、说明 | 已有 Open-Meteo / CC BY4.0 数据署名 | 保留代码和署名，不带位置/种子/缓存 |
| AndroidManifest.xml | 应用、权限和入口声明 | 未发现第三方代码片段声明 | 纳入，保留原 API/版本配置 |
| res/values/strings.xml | 应用名 | 本次重新生成的名称资源 | 纳入原创资源 |
| res/drawable/icon.xml | Android vector 标识 | 本次原创电视/地平线图形 | 纳入，替代旧 vector |
| app/assets/offline-gallery.json | 空图库 | 本次原创空清单，不含作品 ID | 纳入 |
| tools/build_unsigned.py | 固定白名单编译和 unsigned 打包 | 本次新写，使用标准 Android/JDK 工具 | 纳入，不迁入旧签名/安装脚本 |

完整 CleanYourTV MIT 文本与字体例外见 [第三方声明](../third_party/cleanyourtv-LICENSE.txt)。本候选没有分发其 Outfit 字体，也没有将 Noto、Lucide/Feather、Wallhaven 或其他照片随源码迁入。
