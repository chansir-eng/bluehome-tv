# 构建与依赖

[返回首页](../README.md)

应用使用 Android/JDK 标准 API，包括 Android 内置 `org.json`，未发现 Maven 第三方库、AndroidX 或 NDK 依赖。本候选保留旧 API 配置，未自行升级架构。

## 本地前置工具

| 工具 | 要求 |
| --- | --- |
| Python | 3.9+，仅标准库 |
| JDK | 本轮采用现有 JDK 17；使用 javac 和 java |
| Android 平台 | API22 的 `android.jar` |
| Android build-tools | 包含 aapt、zipalign、lib/d8.jar；本地现有工具目录为 34 系列 |

不附带 SDK/JDK 二进制，不自动联网下载依赖，也不迁入旧工程构建、签名或安装脚本。调用者应使用自己已有、来源可信的工具。

## unsigned 构建入口

```text
python tools/build_unsigned.py --java-home <existing-jdk-17> --android-jar <existing-api22-android.jar> --build-tools <existing-build-tools>
```

命令参数是本机工具路径，不写入仓库。脚本执行：aapt 编译资源并生成 R → javac Java8 目标 → D8 minAPI21 → 写入 dex → zipalign 与包信息检查。

脚本只读取固定的 14 个 Java、Manifest、两份指定 XML 和原创空图库；不自动打包额外 assets、真实位置文件或示例配置。输出保存在新建 `build/unsigned-*` 目录，包括 unsigned APK、工具/产物校验值和仅本地构建日志。`build/` 不在发布白名单中。

没有签名、签名凭据、adb、设备访问、软件安装、联网下载或递归清理操作。Unsigned APK 不是可直接覆盖原应用的发行包。

## 验证状态

2026-10-26 已使用本机既有 JDK17、API22 与 build-tools 成功完成一次 unsigned 构建：14 Java 编译、D8、资源打包、zipalign 及包信息检查通过；核验唯一随包资产为原创空图库，没有签名或安装。工具校验值和产物信息保留在仅本地构建记录中。

此结果证明当前候选能用已知本地工具链构建，不保证不同工具版本结果相同或字节级可复现；更不代表电视运行、遥控、投屏、启动效果或视觉布局已验证。无素材候选与既有安装版存在明确视觉及配置差异。

现有包名为 `local.tv.bluehome`，minSdk21、targetSdk22，3.3.1/code11；没有据此承诺所有 Android 5.0+ 电视兼容。未来修改目标 API、增加功能或资源后应重新构建与做对应回归。
