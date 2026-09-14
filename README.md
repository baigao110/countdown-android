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

## 应用内更新机制（已与 GitHub Release 同步）

关于页的「更新」按钮**直接读取本仓库的最新 Release**，无需人工维护版本文件：

```
GET https://api.github.com/repos/baigao110/countdown-android/releases/latest
```

- **版本号**：取 Release 的 `tag_name`（支持 `v1.1.0` / `1.1.0` 两种写法），按「主版本号×10000 + 次版本号×100 + 修订号」转成数值与当前版本比较。
- **下载地址**：自动取该 Release 中第一个 `.apk` 资产的 `browser_download_url`；若没有 APK 资产则退回到 Release 页面地址。
- **更新说明**：取 Release 的 `body`（正文超 200 字会截断），并附上 APK 体积。
- **回退通道**：若 GitHub 接口不可达（网络受限等），自动改读仓库根目录的 `update.json`：

```json
{
  "versionCode": 1,
  "versionName": "1.0.0",
  "apkUrl": "https://github.com/baigao110/countdown-android/releases/download/v1.0.0/countdown-android-v1.0.0-release.apk",
  "note": "倒计时安卓版 v1.0.0 首发版本"
}
```

### 发布新版本的正确姿势

1. 在 GitHub 仓库发一个 **新 Release**，tag 填 `v1.1.0`（版本号要比当前大），标题/正文写更新说明；
2. 把签名好的 APK 作为资产上传（文件名建议保持 `countdown-android-v<版本>-release.apk`）；
3. 完成 —— 用户打开 App 关于页点「更新」就会自动识别并提示下载，**不必再改 `update.json`**。

> 提示：`update.json` 现已降级为备用通道，仅在 GitHub 接口不可用时生效，可不维护。

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
