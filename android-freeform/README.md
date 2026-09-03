# MacFreeform

Android app that launches other apps into **freeform windows**, decorates those
windows with macOS-style traffic-light captions, and floats a magnifying macOS
dock over everything. The app's own UI is frosted glass.

## Build

1. Android Studio → *Open* → select this `android-freeform` folder.
2. Let Gradle sync (AGP 8.5.2 / Kotlin 1.9.24 / JDK 17).
3. `Build → Build Bundle(s)/APK(s) → Build APK(s)`, or from a terminal:

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

(Add the Gradle wrapper with `gradle wrapper --gradle-version 8.7` if you don't
have one, or just build from Android Studio which supplies it.)

## One-time device setup

Freeform is a system capability — the app can only ask for it, so flip these
flags once:

```bash
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb shell settings put global development_force_resizable_activities 1
# lets the app reflect into ActivityOptions.setLaunchWindowingMode (Android 9+)
adb shell settings put global hidden_api_policy 1
adb reboot
```

Developer Options → **Enable freeform windows** + **Force activities to be
resizable** set the first two for you.

In the app:
- grant **Display over other apps** (dock overlay), then tap *Start Dock*
- enable **MacFreeform** under Settings → Accessibility (window captions)

Optional, only on rooted / AOSP builds, for real window move + zoom:

```bash
adb shell pm grant com.angos.freeform android.permission.MANAGE_ACTIVITY_TASKS
```

## What each piece does

| File | Role |
|---|---|
| `core/FreeformLauncher.kt` | Reflects `ActivityOptions.setLaunchWindowingMode(5)`, sets cascade `launchBounds`, starts the target app. Also `setTaskWindowingMode`/`resizeTask` via `ActivityTaskManager` for moving live tasks. |
| `core/AppRepository.kt` | Enumerates launchable packages + icons. |
| `core/DockPrefs.kt` | Persists dock contents and options. |
| `service/DockService.kt` | Foreground service holding a `TYPE_APPLICATION_OVERLAY` window with `FLAG_BLUR_BEHIND` (real frosted glass on Android 12+). |
| `service/DockView.kt` | The dock: gaussian magnification around the pointer, running dots, tooltips, launch bounce. |
| `service/WindowDecorService.kt` | AccessibilityService that enumerates on-screen windows, detects freeform ones by bounds, and paints a `CaptionView` (frosted bar + close/minimise/zoom lights) on each. |
| `ui/Glass.kt`, `ui/Theme.kt` | Frosted-glass surface primitive and the macOS palette/typography. |
| `MainActivity.kt` | Frosted-glass launcher UI with its own traffic-light chrome, setup checklist, search, app grid, dock editor. |

## Honest limitations

- `setLaunchWindowingMode` is `@hide`. Without `hidden_api_policy 1` (or a
  Shizuku/root shim) Android blocks the reflective call and apps open
  fullscreen — the app detects this and tells you.
- You cannot repaint the system's own freeform caption from userspace. The
  caption here is an accessibility overlay drawn on top of it. On ROMs that
  honour `settings put global freeform_caption_height 0` the stock caption
  disappears and only the macOS one remains.
- Moving/resizing a task really requires `MANAGE_ACTIVITY_TASKS` (signature
  permission). Without it, dragging moves the caption overlay only.
- OEM skins (MIUI, One UI, ColorOS) reimplement multi-window and often ignore
  these flags entirely; AOSP, Pixel and GSI builds behave best.

## How freeform launching actually works (v2)

Setting `ActivityOptions.setLaunchWindowingMode(5)` on its own is not enough on
most builds — the framework drops the launch back to fullscreen. The reliable
trick (same one farmerbb/Taskbar uses) is:

1. Start `core/InvisibleActivityFreeform` — a transparent 1x1 px activity placed
   just off the bottom-right corner of the display — *with* the reflected
   freeform windowing mode and explicit `launchBounds`. It is invisible but it
   genuinely occupies a task in the freeform stack.
2. Wait ~100 ms (300 ms on Android 11+) for that task to settle.
3. Start the real app with plain `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_SINGLE_TOP`
   and its own `launchBounds`. Because a freeform task is already active,
   `ActivityStarter` puts the new activity in freeform too.

Notes:
- `FLAG_ACTIVITY_MULTIPLE_TASK` / `FLAG_ACTIVITY_LAUNCH_ADJACENT` on the real
  launch push it out of the freeform stack — they were removed.
- The anchor activity is `singleInstance`, `excludeFromRecents`, has its own
  `taskAffinity` and a fully transparent, animation-free theme.
- **Display over other apps must be granted**, otherwise the anchor cannot be
  started and everything falls back to fullscreen.
- Pre-Pie devices use `setLaunchStackId(2)` instead of `setLaunchWindowingMode(5)`.


## Troubleshooting (updated)

### "Unable to start intent" / app opens fullscreen
The launcher now mirrors Taskbar exactly:
* explicit `ACTION_MAIN` + `CATEGORY_LAUNCHER` intent on the resolved
  `ComponentName` (never `getLaunchIntentForPackage`, whose extra flags break
  the freeform stack),
* hidden-API greylist lifted via `VMRuntime.setHiddenApiExemptions`,
* `ActivityOptions.setLaunchWindowingMode(5)` + launch bounds only (no
  `setLaunchActivityType`, which Android 12+ rejects),
* cascading fallbacks: bounds+freeform -> freeform only ->
  `LauncherApps.startMainActivity` -> plain start, each catching
  `IllegalArgumentException` / `SecurityException`.

Still fullscreen? Run:
```
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb shell settings put global hidden_api_policy 1
adb reboot
```

### Dock not visible over other apps
Enable **MacFreeform** under Settings > Accessibility. The dock is then added as
a `TYPE_ACCESSIBILITY_OVERLAY`, which draws above every application window
(including freeform windows and OEM skins that suppress ordinary app overlays).
Without it, the dock falls back to the "Display over other apps" overlay layer.
