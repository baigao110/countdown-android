# 倒计时（Android 版）

一款轻量、无广告、纯原生 Kotlin 编写的 Android 倒计时工具。
添加好倒计时后可在主界面查看，也能让它悬浮在其它应用之上，随时瞄一眼还剩多久。

- 包名：`com.baigao.countdown`
- 最低系统：Android 8.0（API 26）／目标 API 34
- 依赖：**零第三方库**，只用 Android 原生控件（自 Android 12 起启用 RenderEffect 模糊做液态玻璃）
- 版权署名：`Win11倒计时工具BYbaigao110`

---

## 功能特性

### 倒计时管理

- **自由添加 / 编辑 / 删除**：标题、目标日期时间、备注，全部可改
- **8 种显示模式**（点卡片上的「模式」按钮循环切换，模式恒定输出所选单位，不会随剩余量自动变格式）：

  | 模式 | 示例 |
  |---|---|
  | 标准模式 | 3周5天08:20:15 |
  | 小时模式 | 605时 |
  | 分钟模式 | 36320分 |
  | 秒模式 | 2179215秒 |
  | 天数模式 | 25天 |
  | 时分秒模式 | 08:20:15 |
  | 天时分秒模式 | 25天08:20:15 |
  | 天时分模式 | 25天08:20 |

- **8 种跳秒动画**（点「动画」按钮循环切换）：无动画 / 缩放 / 蒸发 / 坠落 / 像素化 / 碎片化 / 燃烧 / 震撼
  — 动画只作用在**末位数字**上，且只在数字真的变化时才播放
- **每个倒计时独立提示音**：点卡片上的提示音名称即可打开系统铃声选择器
- **主题颜色**：可为每条倒计时单独指定
- **长按拖动排序**，松手即保存
- **左滑卡片**露出「编辑 / 提示音 / 删除」操作

### 悬浮窗

- 逐条控制在悬浮窗中显示 / 隐藏
- 可拖动位置、点标题栏收缩成小条、调节不透明度（20%~100%）
- 走秒**整秒同步**：所有倒计时共用同一时刻基准，在同一瞬间一起跳秒
- 已知限制：系统「设置」界面处于前台时悬浮窗会被系统临时隐藏（Android 安全机制），返回后自动恢复；
  若任意界面都不显示，请在系统设置中开启「显示在其他应用上层」，并在品牌权限管理中开启「后台弹出界面」

### 内置倒计时

自带四个：**当日倒计时、当月倒计时、华都云境悦府（2026-10-31 00:00:00）、GTA6（2026-11-19 08:00:00）**

- 滚动型（当日 / 当月）每天、每月自动重算目标时间；固定型到期后停在 0，不会自动滚到下一周期
- 内置项**可以删除、可以修改**；一旦改了目标时间就降级为普通倒计时
- 删除时会保存一份**设置快照**，之后在「＋」菜单里点「恢复内置」找回时，
  会一并还原删除前的主题颜色、显示模式、跳秒动画、提示音、悬浮窗设置与备注，只重新计算目标时间
- 四个内置项都在列表里时，「恢复内置」菜单项自动隐藏

### 更新与通知

- 打开 App 自动检查 GitHub 最新版本，发现新版本弹更新日志，「立即更新」即在 App 内下载并拉起安装
- 后台双路检查：JobScheduler（有网才跑，15 分钟一趟，重启自动恢复）+ AlarmManager 兜底（2 小时，失败 30 分钟重试）
- 「关于」页提供**通知自检**三入口：开启通知 / 测试通知 / 允许后台运行（关闭电池优化），并显示后台检查状态

---

## 界面说明

| 位置 | 操作 |
|---|---|
| 主界面列表 | 每条卡片：标题、目标时间、剩余时间、按钮行（显示 → 模式 → 动画 → 提示音名称） |
| 卡片左滑 | 露出「编辑 / 提示音 / 删除」 |
| 卡片长按 | 进入拖动排序 |
| 右下角「＋」 | 扇形展开：添加倒计时 / 关于 / 恢复内置（有缺失时才显示） |
| 关于页 | 检查更新 / **说明** / 更新日志 / 开启通知 / 测试通知 / 允许后台运行 |

---

## 下载安装

从 Releases 下载最新 APK 直接安装即可（需允许「安装未知应用」）：

<https://github.com/baigao110/countdown-android/releases/latest>

App 内的版本信息来自仓库根目录的 `update.json`（含 versionCode、下载地址与更新说明）。

---

## 目录结构

```
CountdownAndroid/
├─ app/src/main/java/com/baigao/countdown/
│  ├─ MainActivity.kt           主界面：列表、拖动排序、悬浮窗开关、恢复内置对话框
│  ├─ Countdown.kt              数据模型、显示模式（MODE_NAMES）、内置类型（BuiltIn）、时间格式化
│  ├─ CountdownStore.kt         本地存储：保存 / 读取（parse / toJson 供落盘与快照共用）
│  ├─ AddEditActivity.kt        添加 / 编辑页面
│  ├─ CountdownService.kt       前台服务，维持悬浮窗
│  ├─ FloatingView.kt           悬浮窗视图与交互（拖动、收缩、透明度）
│  ├─ CountdownAnimator.kt      8 种跳秒动画
│  ├─ SoundPlayer.kt / SoundNames.kt  提示音播放与名称解析
│  ├─ GlassBackdrop.kt          液态玻璃背景（Android 12+ 用 RenderEffect 模糊）
│  ├─ UpdateManager.kt          版本检查、下载安装、更新日志、统一风格对话框、使用说明
│  ├─ UpdateNotifier.kt         更新通知渠道与发送
│  ├─ UpdateCheckJobService.kt  JobScheduler 后台检查
│  ├─ UpdateCheckReceiver.kt    AlarmManager 后台检查（兜底）
│  └─ AboutActivity.kt          关于页
├─ app/src/main/res/            布局、drawable（glass_* 玻璃质感）、主题
├─ update.json                  版本信息（供 App 内检查更新）
└─ build_apk.bat                编译并签名脚本
```

---

## 编译

需要 JDK 17 + Gradle 8.2 + Android SDK 34（本仓库不含 Gradle Wrapper，使用本机工具链）。

```bat
cd CountdownAndroid
build_apk.bat
```

产物：`countdown-android-v1.0.0.x-release.apk`（已 zipalign + 用 `countdown-release.jks` 签名）。

> 注意：项目路径若含中文，需要保留 `gradle.properties` 中的 `android.overridePathCheck=true`。

---

## 发版清单

1. `app/build.gradle`：`versionCode` +1、`versionName`
2. `UpdateManager.kt`：`CURRENT_VERSION_NAME` 与 `CHANGELOG` 头部追加一条
3. `update.json`：versionCode / versionName / date / apkUrl / htmlUrl / note
4. `build_apk.bat`：同步旧版本号
5. 编译 → 抽查 APK → 同步 GitHub → 回读核验

版本号支持四段，`versionToNumber = major*1e6 + minor*1e4 + patch*100 + build`；
**versionCode 必须递增**，发布后确认 `/releases/latest` 指向新版。

---

## 同系列

Windows 桌面版（WinForms）位于上级目录 `CountdownTool/`，双击 `编译生成exe.bat` 可自行编译出 `Win11倒计时工具.exe`。

---

`Win11倒计时工具BYbaigao110`
