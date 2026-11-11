# Time Log Android (test build)

A small native shell around the Time Log web app ([live](https://naarrzz.github.io/time-log/) · [source](https://github.com/naarrzz/time-log)). The web app still does all the logging. This adds what a browser tab can't:

- **Live notification:** the running tile as an ongoing notification with a timer, its color, and Stop. On Android 16 it asks to be a Live Update (status bar chip).
- **Home screen widget:** running tile and timer, today's total, and up to 4 tile shortcuts (most used first). "Nothing running" otherwise. Resizable.
- **Reminders:** a tile still running after 4 hours, and gaps left today at 9 PM. Inexact alarms, so they can be a few minutes late.

Not on the Play Store.

## Download
**[Download TimeLog-debug.apk](https://github.com/naarrzz/time-log-android/releases/latest/download/TimeLog-debug.apk)** (latest release, Android 8.0 and up). It's a debug build, so Android will warn that it's from an unknown source. See [Put it on your phone](#put-it-on-your-phone-sideload) below.

## Screenshots
Coming soon. Planned shots (saved in `docs/screenshots/`):

| File | What it shows |
| --- | --- |
| `app.png` | The app open on Ngayon |
| `notification.png` | The running timer in the notification shade, with Stop |
| `chip.png` | The Live Update chip in the status bar (Android 16) |
| `widget.png` | The home screen widget with a tile running |

## How it talks to the web app
The page posts its state to `window.TimeLogAndroid.postMessage(json)`, the same JSON the iOS app gets:

```
{ running: {id, name, color, start, logStart, sub} | null, tiles: [{id, name, color}], top: [ids], today: [{id, name, color, ms}], gaps: n, day }
```

`{ui: {...}}` messages are ignored (Android keeps the web tab bar). Messages are only accepted while the page is on naarrzz.github.io. The last snapshot is kept in SharedPreferences for the widget, reminders and reboots.

Notification and widget taps open `timelog://open`, `timelog://stop` or `timelog://tile/<id>`, and the app calls `window.timeLogAction('stop' | 'tile' | 'refresh', id)`, so the web app stays the only thing that writes logs. Actions wait until the page has loaded; `refresh` runs every time the app comes back to the front.

Until the web app supports `window.TimeLogAndroid`, the app still works as a plain wrapper; the notification and widget just stay empty.

## Build
Needs JDK 17 and the Android SDK (platform 36, build-tools 36.0.0). On a Mac with Homebrew:

```
brew install openjdk@17
brew install --cask android-commandlinetools
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug lintDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Put it on your phone (sideload)
No computer, cable, USB debugging or Developer options needed. Everything happens on the phone:

1. On the phone, tap the [Download link](https://github.com/naarrzz/time-log-android/releases/latest/download/TimeLog-debug.apk) above.
2. Open the downloaded file. Android asks to allow installs from that app (Chrome or Files): allow it once.
3. Install, open Time Log, allow notifications, and sign in.
4. Long-press the home screen > Widgets > Time Log to add the widget.

To update, download the newer APK and install it over the old one; your login stays. This works as long as both builds are signed with the same debug key (built on the same Mac).

For developers only: with USB debugging on, `adb install -r app-debug.apk` also works.

## Not done yet
- Backup download from the page (blob downloads) isn't wired up in the WebView.
- Not tested on a real phone or emulator yet.
