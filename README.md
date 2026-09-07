# 倒计时（安卓版）

一个轻量、零第三方依赖的原生 Android 倒计时工具，支持桌面小组件式悬浮窗、多种显示模式、自定义提示音，以及应用内更新检测。

> 版本号：**v1.0.0**（versionCode = 1）。本仓库源码与 APK 均对应此版本。

## 功能特性

- **扇形快捷菜单**：主界面右下角「+」按钮，点击扇形弹出「关于」「添加倒计时」两项；点击空白处自动收起。
- **动态倒计时**：主界面列表与悬浮窗均每秒实时跳动，二者显示模式双向同步。
- **悬浮窗倒计时**：前台服务常驻，需要「显示在其他应用上层」权限；首次使用会引导开启。
- **多种显示模式**：标准 / 小时 / 分钟 / 秒 / 天数 / 时分秒 / 天时分秒 / 周 等共 10 种。
- **自定义提示音**：可选择系统铃声作为归零提示音，并显示铃声名称。
- **长按拖动排序**：列表项可长按拖动重新排序，拖动时原条目虚化。
- **关于页面与更新检测**：关于页展示放大图标、版本号与「更新」按钮；点击后联网比对远程版本，有新版本则打开下载链接。

## 截图与界面

- 主界面：标题 + 倒计时列表 + 右下角「+」悬浮按钮。
- 关于页：中间靠上放大的倒计时图标、版本号、更新按钮。

## 构建与签名

环境要求：JDK 17、Android SDK（compileSdk / targetSdk 34）、Gradle 8.2。

```bash
# 1. 编译 Release 未签名 APK
gradle assembleRelease

# 2. zipalign 对齐
$ANDROID_HOME/build-tools/34.0.0/zipalign -f 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk app-release-aligned.apk

# 3. 签名（密钥与口令见 build_apk.bat）
$ANDROID_HOME/build-tools/34.0.0/apksigner.bat sign \
  --ks countdown-release.jks --ks-key-alias countdown \
  --ks-pass pass:**** --key-pass pass:**** \
  --out 倒计时-安卓版-v1.0.0-release.apk app-release-aligned.apk
```

Windows 用户可直接双击 `build_apk.bat` 完成上述全部步骤。

> ⚠️ 注意：`countdown-release.jks` 为签名密钥，**已通过 `.gitignore` 排除，不会提交到仓库**；如需自行构建 Release，请使用你自己的密钥。

## 应用内更新机制

关于页的「更新」按钮会请求仓库根目录的 `update.json`：

```json
{
  "versionCode": 1,
  "versionName": "1.0.0",
  "apkUrl": "https://github.com/baigao110/countdown-android/releases/download/v1.0.0/countdown-android-v1.0.0-release.apk",
  "note": "倒计时安卓版 v1.0.0 首发版本"
}
```

- 远程 `versionCode` 大于当前版本号 → 按钮变为「下载更新 vX」，点击打开 `apkUrl` 下载安装。
- 相等或无更新 → 提示「已是最新版本」。
- 检测地址由 `AboutActivity.kt` 中的 `UPDATE_URL` 常量定义，部署后请改为实际仓库路径。

## 目录结构

```
CountdownAndroid/
├── app/src/main/            # 源码与资源
│   ├── java/com/baigao/countdown/
│   ├── res/layout/          # 界面布局
│   └── AndroidManifest.xml
├── build_apk.bat            # 一键打包脚本（Windows）
├── update.json              # 远程版本信息（更新检测用）
└── countdown-release.jks    # 签名密钥（不入库）
```

## 权限说明

- `SYSTEM_ALERT_WINDOW`：悬浮窗显示在其他应用上层。
- `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS`：后台常驻倒计时通知。
- `INTERNET`：关于页检查更新。
- `RECEIVE_BOOT_COMPLETED`：开机自启悬浮倒计时。

## 许可

仅供个人学习与非商业使用。
