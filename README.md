# Time Log Android (test build)

A small native shell around the Time Log web app (naarrzz.github.io/time-log). The web app still does all the logging. This adds what a browser tab can't:

- **Live notification:** the running tile as an ongoing notification with a timer, its color, and Stop. On Android 16 it asks to be a Live Update (status bar chip).
- **Home screen widget:** running tile and timer, today's total, and up to 4 tile shortcuts (most used first). "Nothing running" otherwise. Resizable.
- **Reminders:** a tile still running after 4 hours, and gaps left today at 9 PM. Inexact alarms, so they can be a few minutes late.

Not on the Play Store. Private repo.

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
1. Copy the APK to the phone (Google Drive, USB, or send it to yourself).
2. Open it from Files. Android asks to allow installs from that app (Chrome, Files, Drive): allow it once.
3. Install, open Time Log, allow notifications, and sign in.
4. Long-press the home screen > Widgets > Time Log to add the widget.

With USB debugging on, `adb install -r app-debug.apk` works too. Updates install over the old one as long as they're signed with the same debug key (the same Mac).

## Not done yet
- Backup download from the page (blob downloads) isn't wired up in the WebView.
- Not tested on a real phone or emulator yet.
