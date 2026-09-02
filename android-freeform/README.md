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
