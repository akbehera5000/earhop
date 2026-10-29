# Codex handoff, 2026-10-29

This note summarizes the clipboard work completed by Codex for the next assistant session. Read `AGENTS.md` first and inspect `git status` and the diffs before changing anything. The working tree contains other uncommitted work from earlier sessions as well as the clipboard changes listed here. Do not discard or overwrite it.

## Clipboard changes

### Existing helper path retained

The Android phone's existing `ClipHelper` runs as Android's shell user, which can monitor clipboard changes in the background. It passes text to `ClipHelperProvider`, where the app checks the calling UID before sending it. EarHop can start it through Shizuku, or it can be started from a computer using `tools/phone_clip.sh`. Rooted devices can use the existing root based method. Keep this path available.

`HelperStarter.kt` starts the helper through Shizuku when Shizuku is running as UID 2000 and has granted EarHop permission. `ClipSync.watchHelper` checks for a helper hello and periodically tries to restart it. Shizuku itself is not started by EarHop.

### Accessibility path added

`ClipAccessibilityService.kt` is an optional fallback for unrooted devices. It listens only for notification state accessibility events, matches a short list of generic copy confirmation phrases, and opens the existing `ClipActivity` with automatic sending enabled. It does not request permission to retrieve window contents and does not log event contents or clipboard contents. Detection is heuristic and can miss apps whose copy confirmation differs.

The accessibility service is registered in `android/app/src/main/AndroidManifest.xml`, with event configuration in `android/app/src/main/res/xml/clip_accessibility_service.xml`. Its Android settings description is in `android/app/src/main/res/values/strings.xml`.

### Clipboard UI selection and status

The Clipboard page in `MainActivity.kt` has an Automatic sending method selector: Helper or Accessibility. Helper selection shows the previous helper and Shizuku setup. Accessibility selection hides helper setup and shows accessibility setup. The UI reads Android's `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` to show whether EarHop's service is On or Off. The settings button opens Android Accessibility settings so the user can enable it.

The method preference is `clipAutoMethod`, with values `helper` and `accessibility`. The accessibility trigger preference is `clipAccessibilityTrigger`. Automatic sending still depends on `clipAutoRoot` and clipboard sync being enabled. Incoming clipboard sync is independent of this choice.

## Build and device state

* The updated EarHop debug APK builds successfully with `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug`.
* The APK was copied to `dist/earhop new.apk` and installed by ADB on both the phone and rooted tablet. Installed package reports version 1.1.0, versionCode 6.
* Shizuku v13.6.0 was installed on the phone from the official RikkaApps GitHub release. It still requires the owner to open Shizuku, start it with Wireless debugging, and allow EarHop. Do not assume Shizuku is running or authorized.
* Shizuku was not installed on the rooted tablet. Its root helper path should be used there.
* Accessibility event detection and helper behavior have not yet been validated with real copy operations on either device.

## Files touched for this work

* `android/app/src/main/kotlin/com/earhop/logger/ClipAccessibilityService.kt` (new)
* `android/app/src/main/kotlin/com/earhop/logger/HelperStarter.kt` (new from the earlier Shizuku implementation)
* `android/app/src/main/kotlin/com/earhop/logger/ClipSync.kt`
* `android/app/src/main/kotlin/com/earhop/logger/MainActivity.kt`
* `android/app/src/main/AndroidManifest.xml`
* `android/app/src/main/res/xml/clip_accessibility_service.xml` (new)
* `android/app/src/main/res/values/strings.xml`
* `CHANGELOG.md` and `HANDOFF.md`

No tests were run as part of these UI iterations. The Android assembleDebug build succeeded, which compiles the Kotlin service and resources.
