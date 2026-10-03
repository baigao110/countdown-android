# Countdown (Android Version)

A lightweight, ad-free Android countdown tool written in native Kotlin.
Once you add a countdown you can see it on the main screen, or let it float above other apps
so you can glance at how much time is left at any moment.

> 该软件暂无英语或其他版本，请见谅！
>
> *This app is currently only available in Simplified Chinese — there is no English or other
> language version yet. Thank you for your understanding!*

---

## Screenshots

| Countdown list | Add / Edit |
|---|---|
| ![screenshot-main-list](screenshots/main-list.png) | ![screenshot-add-edit](screenshots/add-edit-page.png) |

| Floating window | Medicine calendar |
|---|---|
| ![screenshot-floating](screenshots/floating-window.png) | ![screenshot-calendar](screenshots/medicine-calendar.png) |

- Main list: every countdown is a glass card; under the title is the remaining time,
  and the button row is 显示 (display) → 模式 (mode) → 动画 (animation) → 提示音 (sound).
- Floating window: semi-transparent, floats above other apps, draggable with an opacity slider,
  and ticks in sync with the list down to the second.
- Medicine calendar: solid cyan = dose taken, pale red = missed, cyan outline = today.

---

## Features

### Countdown management

- **Add / edit / delete freely**: title, target date & time, remark — all editable
- **8 display modes** (tap the 模式 button on a card to cycle through; the chosen mode always
  wins, the format never changes on its own):

  | Mode | Example |
  |---|---|
  | Standard | 3周5天08:20:15 |
  | Hours | 605时 |
  | Minutes | 36320分 |
  | Seconds | 2179215秒 |
  | Days | 25天 |
  | Day + H:M:S | 08:20:15 |
  | Day + H:M:S + days | 25天08:20:15 |
  | Day + H:M + days | 25天08:20 |

- **8 tick animations** (tap the 动画 button to cycle): none / zoom / evaporate / fall / pixelate /
  shatter / burn / shake — the animation applies only to the **last digit** and only when it actually changes
- **Independent ringtone per countdown**: tap the sound name on the card to open the system
  ringtone picker
- **Theme color** can be set for each countdown
- **Long-press to reorder**, order is saved as soon as you release
- **Swipe a card left** to reveal 编辑 (edit) / 删除 (delete); swiping back or dismissing the
  confirmation dialog slides the row home again

### Floating window

- Show / hide each countdown in the floating window individually
- Draggable, collapse into a small bar by tapping the title bar, opacity adjustable (20%–100%)
- **Whole-second sync**: all countdowns share one time base and tick together at the same instant
- Known limitation: the floating window is temporarily hidden by the system while the system
  Settings screen is in the foreground (Android security behavior) and comes back afterwards;
  if it never shows up, enable "Display over other apps" in system settings and allow
  "show pop-ups in background" in the brand-specific permission manager

### Daily medicine reminder

- By default one system notification per day at **09:00**; tap **已吃药** (taken) right on the
  notification to record the day
- The reminder time is changeable: open 关于 (About) → **提醒时间** and pick a moment
- Toggle the whole feature off from 关于 with **吃药提醒：已开启 / 已关闭**; past records stay
- **Medicine calendar**: see which days were taken and which were missed, month by month —
  solid cyan = taken, pale red = missed, cyan outline = today, future days cannot be recorded
  - ‹ › at the top switches month; a bottom button records / undoes today in one tap
  - Tap a past or today's cell to fix or undo the record
- **Keeps firing after the app exits** (several redundant mechanisms):
  ① system alarm clock (`setAlarmClock`, highest priority, survives Doze, needs no exact-alarm
  permission); ② exact alarm (`setExactAndAllowWhileIdle`) as a second route at the same moment;
  ③ a daily repeating alarm as a fallback if the main route gets cleared; ④ retry bells 2 and
  30 minutes after the due time; ⑤ a background job (every 15 minutes, restored after reboot)
  that re-arms a missing alarm and re-sends a missed notification (tagged 补发提醒)
- **Next reminder can be rescheduled**: 关于 → **下次提醒** (the button shows the current time)
  lets you pick an exact date + time — e.g. if today's 09:00 was missed, move just this one to
  tonight 20:30; it lasts for that single time only and then reverts to the daily slot, and can be
  reset with 恢复常规. A manually moved reminder **always fires** (it bypasses the
  "once per day" de-duplication)
- Only one reminder per day; a day you already marked as taken stays quiet
- Changing 下次提醒 pops a guide that can jump straight to "allow background activity"
  (disable battery optimization); coming back to the foreground also re-sends a missed reminder
- If nothing arrives, open **后台自检** in 关于: it lists the next reminder time, the alarm's
  scheduling time, **the moment a reminder actually fired last time**, notification permission,
  exact-alarm permission and battery-optimization state — so you can tell "never fired" from
  "fired but blocked"

### Built-in countdowns

Seven built in: **today, every hour, every half hour, this week, this month,
华都云境悦府 (fixed: 2026-10-31 00:00:00), GTA6 (fixed: 2026-11-19 08:00:00)**

- Rolling ones (today / week / month) recompute the target every day, week or month;
  fixed ones stop at 0 when due instead of rolling to the next cycle
- The weekly target is **next Monday 00:00:00** — the moment this week ends (Monday is the first day)
- Built-ins **can be deleted and edited**; once you change the target it becomes a normal countdown
- Deleting stores a **settings snapshot**, so restoring a built-in from the ＋ menu brings back
  its theme color, display mode, tick animation, ringtone, floating-window settings and remark,
  and only recalculates the target time
- The 恢复内置 menu item hides itself when all seven built-ins are present

#### Display modes for built-ins

Rolling built-ins have different cycle lengths, so what 标准模式 should show differs (other modes
behave the same for every countdown):

| Built-in | Cycle | Standard mode |
| --- | --- | --- |
| Today | ≤ 24 hours | **xxh xxm xxs** (week / day always 0) |
| This week | ≤ 7 days | **xx days xxh xxm xxs** (week always 0) |
| This month, 华都云境悦府, GTA6 | long | xw xd xxh xxm xxs (same as a normal countdown) |

Also, the **today** countdown has no **days** mode (its day count is always 0) — it no longer
appears in the list, the floating window or the edit page, and older records using that mode fall
back to standard mode automatically.

### Updates and notifications

- The app checks GitHub for a newer version when it opens, and prompts to update with an
  in-app download and install
  - The prompt is a **dedicated page** (transparent background + the same glass card as dialogs),
    not a system dialog: "skip splash ads / popup blockers / ad filters" watch for dialog windows
    and would kill an update prompt — a plain page launched 800 ms later is not mistaken for an ad;
    the download progress is shown on the same card
- Dual background checks: JobScheduler (network required, every 15 minutes, restored after reboot)
  + AlarmManager fallback (every 2 hours, retry after 30 minutes on failure)
- 关于 offers a **notification check** with three shortcuts: enable notifications / test
  notification / allow background activity, plus the background check status

---

### My notifications don't arrive

If nothing shows up after the app exits or is closed (both the medicine reminder and the update
notification count), it is almost never a missing schedule — the notification is **blocked at the
last step**: permission denied, channel downgraded, or background restricted. All three look
identical in code: it runs fine, no error, nothing appears.

So start with **通知体检** in 关于 — it walks the gates one by one, top to bottom:

- The **bottom button names the first fix**: for whichever item isn't a check mark it reads
  「去修第 N 项」and jumps right to that item's settings
- Every row also has its own shortcut button, numbered to match
- When everything is fine, no 去修 button is shown, only a centered 关闭
- Numbering is generated from the actual item count (Android 13 and below have no
  "notification permission" row), so never hard-code it

| Check | What breaks if it fails |
| --- | --- |
| System notification master switch | nothing is ever shown |
| Notification permission (Android 13+) | sends are dropped silently, not even a log |
| Medicine reminder channel | downgraded to silent, discarded without a trace |
| Update channel | same |
| Exact alarm | the moment may drift (system alarm + repeating alarm + job fallback cover it) |
| Battery optimization | background frozen, nobody fires at the due time |
| Background activity limit | background barely runs |
| Background job | not run in over 3 hours → the chain is broken |

Hardening on the app side:

- **Notification channels were recreated at the highest importance** (rings even in Do Not
  Disturb, visible on the lock screen); once a channel's importance is fixed the system will not
  change it, so a disabled channel is **deleted and recreated** instead of being a dead end
- The medicine reminder **has a full-screen alert**: it pops the medicine page up even on a
  locked screen
- **More moments auto re-arm**: boot, upgrade (alarms are cleared on install), unlock, time and
  timezone changes, day change
- If notifications turn out to be blocked on entry, the app **reminds you once** (max once a day,
  three times in total)

### How soon a new build reaches me

There is no push server of its own — the app can only **ask GitHub periodically**, so it can't be
instant. What it does today:

- Background check every **20 minutes** (plus one the system schedules every 15 minutes);
  **1 minute** after boot / unlock / upgrade
- Checks once every time the app is opened
- During screen-off Doze the system holds checks back; that is Android power saving, same for any app

### My update prompt gets killed by a blocker app

"Skip splash ads / popup blockers / ad filters" work through an **accessibility service watching
windows** and tap anything that looks like a dialog. They cannot touch a notification, so 关于 has
a **更新提示** button with three modes:

| Mode | Behavior |
| --- | --- |
| Auto | switches to notification-only when it detects a non-system, non-input accessibility service |
| Popup | always shows the update page (may be killed by a blocker) |
| Notification only | no UI at all, one notification bar entry (hardest to block) |

On top of that, the prompt page's delay was raised from 800 ms to 1.5 s to stay clear of the
"pops up right at launch" signature; arriving via the notification or the manual 检查更新 button
still opens the page directly — that is user-requested, so it isn't downgraded.

## Interface cheat sheet

| Where | What it does |
|---|---|
| Main list | per card: title, target time, remaining time, button row (显示 → 模式 → 动画 → 提示音) |
| Swipe left | reveals 编辑 / 删除 |
| Long press | drag to reorder |
| ＋ (bottom right) | fans out: add countdown / 关于 / restore built-ins (only when one is missing) |
| 关于 page | check update / **说明** / changelog / enable notification / test notification / allow background / **更新提示方式** / **通知体检** |
| 关于 · medicine | switch · daily reminder time · next reminder (one-off) · medicine calendar · test reminder · background self-check |

---

## Download

Download the latest APK from Releases and install it directly (allow "install unknown apps"):

<https://github.com/baigao110/countdown-android/releases/latest>

The version info inside the app comes from `update.json` at the repository root (versionCode,
download URL and update note).

---

## Project layout

```
CountdownAndroid/
├─ app/src/main/java/com/baigao/countdown/
│  ├─ MainActivity.kt           list, drag reorder, floating window switch, restore built-ins dialog
│  ├─ Countdown.kt              model, display modes (MODE_NAMES), built-ins (BuiltIn), formatting
│  ├─ CountdownStore.kt         local storage: save / load (parse / toJson shared with snapshots)
│  ├─ AddEditActivity.kt        add / edit page
│  ├─ CountdownService.kt       foreground service holding the floating window
│  ├─ FloatingView.kt           floating window view & interaction (drag, collapse, opacity)
│  ├─ CountdownAnimator.kt      8 tick animations
│  ├─ SoundPlayer.kt / SoundNames.kt  ringtone playback and name resolution
│  ├─ GlassBackdrop.kt          liquid glass backdrop (blur via RenderEffect on Android 12+)
│  ├─ UpdateManager.kt          version check, download & install, changelog, styled dialogs, help
│  ├─ UpdateNotifier.kt         update notification channel and sending
│  ├─ UpdateCheckJobService.kt  JobScheduler background check
│  ├─ UpdateCheckReceiver.kt    AlarmManager background check (fallback)
│  ├─ MedicineReminder.kt       medicine reminder: switch / time / records / alarms / notifications
│  ├─ MedicineAlarmReceiver.kt  medicine alarm: fires a notification, 已吃药 records the day
│  ├─ MedicineCheckJobService.kt  background job: re-arm alarms, re-send missed ones
│  ├─ MedicineCalendarActivity.kt medicine calendar (month view, fixable records)
│  └─ AboutActivity.kt          about page
├─ app/src/main/res/            layouts, drawables (glass_*), themes
├─ screenshots/                 README screenshots
├─ update.json                  version info (used by the app's update check)
└─ build_apk.bat                build and sign script
```

---

## Build

Requires JDK 17 + Gradle 8.2 + Android SDK 34 (no Gradle wrapper in this repo; a local toolchain is used).

```bat
cd CountdownAndroid
build_apk.bat
```

Output: `countdown-android-v1.0.0.x-release.apk` (already zipaligned and signed with
`countdown-release.jks`).

> Note: if the project path contains non-ASCII characters, keep
> `android.overridePathCheck=true` in `gradle.properties`.

---

## Release checklist

1. `app/build.gradle`: `versionCode` +1, `versionName`
2. `UpdateManager.kt`: bump `CURRENT_VERSION_NAME` and prepend a CHANGELOG entry
3. `update.json`: versionCode / versionName / date / apkUrl / htmlUrl / note
4. `build_apk.bat`: sync the old version string
5. Build → spot-check the APK → sync to GitHub → read back and verify

Versions support four segments, `versionToNumber = major*1e6 + minor*1e4 + patch*100 + build`;
`versionCode` must increase, and confirm `/releases/latest` points at the new build after release.

---

`Countdown (Android) BY baigao110`

> 该软件暂无英语或其他版本，请见谅！
