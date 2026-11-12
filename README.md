# EarHop

One pair of earbuds, several devices. EarHop moves your AirPods Pro 2 or Galaxy Buds2 Pro to whichever device
starts playing, pauses the device that lost them, and pauses and resumes when you take an earbud out. It runs
entirely on your local network: no cloud, no account.

| Device | App |
| --- | --- |
| Mac (macOS 13 or later, Liquid Glass on macOS 26 and later) | `mac/`, a menu bar app |
| Android phones and tablets (root optional) | `android/`, an app with a foreground service |
| Windows 10 (2004 or later) and 11, 64 bit | `windows/`, a tray app (new, not yet tried on a real PC) |

Any number of devices can join, each with its own name. A Linux app does not exist yet; `docs/PORTING.md` describes
everything one needs.

## How switching works

* **Newest playback wins.** When a device starts playing, it takes the earbuds. Device priority only breaks a near tie (both started within 2.5 s).
* **The old owner pauses, then lets go.** EarHop sends one explicit Pause, never a second toggle, so music never restarts on the speakers.
* **No grabbing back.** A device that let go or lost the earbuds waits until its own audio has really stopped before it can take them again.
* **Calls first.** A device with a call gets the earbuds; the owner pauses and releases.
* **Ear removal.** Taking a bud out pauses; putting it back within 2 minutes resumes, but only if EarHop caused the pause.
* **Safe mode.** After 3 failed switches in a row, automatic switching pauses. Take and Release always work.
* **Speaker fallback.** With the earbuds out, audio you start plays on the device's speakers and moves back when a bud
  goes in. Out for 2 minutes lets go of the earbuds, unless sound is playing on the speakers.
* **Follow my attention** (off by default). Audio started on the device you just unlocked (or, on the Mac, typed on)
  takes the earbuds even if another device is still playing.
* **Meetings first.** On the Mac, sound from Zoom, WhatsApp or Teams counts as a call at once; elsewhere a call is
  noticed from the microphone or Android's call mode.

Both apps run the same state machine (`HandoffCore.swift` and `HandoffCore.kt`) with these states:
Idle, Playing without earbuds, Switching here (claim pending), Playing, Paused by ear removal, Letting go
(release pending), Earbuds moved away, Call priority, Safe mode.

## Build

Mac (no Homebrew tools needed; `blueutil` is optional):

```bash
cd mac
bash make_dmg.sh                                    # builds dist/EarHop-<version>.dmg (universal, with icon)
bash build_app.sh                                   # or: build and install straight into /Applications
EARHOP_NO_BT=1 .build/release/EarHop --selftest     # prints "selftest OK"
```

Install from the DMG: open it, drag EarHop onto Applications, open EarHop from Applications. The version is in
`mac/VERSION`; the icon is drawn by `mac/tools/make_icon.swift` into `mac/Resources/AppIcon.icns`.

The app is signed with a local certificate, not an Apple Developer ID, so it is not notarized. On the Mac that built it,
it opens normally. On any other Mac, macOS blocks the first launch: open System Settings, Privacy & Security, and press
Open Anyway next to EarHop once. A Developer ID (paid Apple Developer account) would remove that step.

Android (needs JDK 17 and the Android SDK):

```bash
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease testDebugUnitTest
```

Install `android/app/build/outputs/apk/release/app-release.apk` (also copied to `dist/earhop new.apk`) on the phone and
the tablet. The release build is signed with the key in `~/.earhop/earhop-release.jks`, configured in
`~/.earhop/android-signing.properties` (storeFile, storePassword, keyAlias, keyPassword). Neither is in the repository;
back them up, because updates must be signed with the same key. Without that file the release APK is unsigned;
`./gradlew assembleDebug` still makes a debug build for development.

Moving from a debug build to the release build (or between two keys) needs one uninstall, which erases EarHop's
settings on that device. Before uninstalling, open Settings, Backup, Export; after installing the new APK, Import the
file, then delete it (it holds the pairing secret). Root permission and the accessibility service must be allowed again.

All tests at once: `bash tests/run.sh`.

## Setup and pairing

1. **Mac:** open EarHop, connect your earbuds to the Mac, press **Detect** in the popover. Allow Bluetooth, Local Network and
   Accessibility when macOS asks. Accessibility is shown as *Device Control and Data Access* on newer macOS.
2. **Pair Android:** on the Mac open Settings, Network, and scan the QR code with the phone camera. EarHop opens with the
   shared secret filled in. Or copy the secret and use **Paste secret**.
3. **Name each device.** Any name works (lower case letters, digits and `-`, up to 20), for example `mac`, `phone`,
   `tab` or `phone2`, but every device needs its own. All devices must run EarHop 1.3 or later.
4. **Accept the pairing request** the first time two devices send a clip over Bluetooth. It appears once per pair of
   devices; after that, Bluetooth sends work at the first attempt.
5. **Android permissions:** Nearby devices, Location (Android needs it for Bluetooth scans), Location switched on,
   Notifications, and Battery set to *No restrictions* (essential on some phones). Notification access and root are optional.
6. Press **Start** on Android and turn on **Live** on the Mac (off is a dry run that only logs decisions).

## Windows

`dist/EarHop-Windows-1.4.1.exe` is one file; it needs no install and no .NET.

1. **Pair the earbuds with the PC once** in Settings, Bluetooth and devices. EarHop then connects and disconnects them
   itself, the way the Sound settings Connect button does.
2. **Start EarHop.exe.** Windows SmartScreen may warn about an unknown publisher (the file is not signed): More info,
   Run anyway. When Windows Firewall asks, allow EarHop on **private** networks (it uses UDP 47474 and TCP 47475).
3. **Settings opens by itself the first time.** Give the PC its own name (for example `pc`), paste the secret from the
   Mac (Settings, Network: copy the secret or its `earhop://pair` link), pick the earbuds, and Save.
4. **Set Connection to Both on your other devices.** The PC sends its status over Wi-Fi only (it listens to Bluetooth
   but does not advertise yet), and a device set to Bluetooth only ignores Wi-Fi status.
5. The EarHop icon sits in the tray (click the ^ next to the clock to find it). Its menu shows the state, your devices,
   Take and Release, Automatic switching, Send clipboard now, Send files, Settings, Copy diagnostics and the log folder.
   Files can also be sent from Explorer: right click, Send to, **EarHop (send to a device)**.

What the Windows app does not do yet: ear detection (it never pauses when you take an earbud out; it switches as if
the earbuds are in), Bluetooth status advertising and Bluetooth clipboard (Wi-Fi only), Galaxy Buds controls,
battery levels, volume handover and shared settings. Log: `%APPDATA%\EarHop\earhop.log`.

Build it on the Mac: `bash windows/build.sh` (needs the .NET 8 SDK from Microsoft's `dotnet-install.sh` in `~/.dotnet`;
the Homebrew build cannot target Windows).

## Permissions explained

| Permission | Why |
| --- | --- |
| Mac Bluetooth | Read the AirPods beacon, talk to the Galaxy Buds status channel, connect and disconnect |
| Mac Local Network | Exchange status with the other devices |
| Mac Accessibility | Only needed if you choose "Play/Pause key only". By default EarHop uses explicit Pause and Play commands |
| Android Nearby devices, Location | Bluetooth scanning and connecting |
| Android battery: No restrictions | Keeps the service alive with the screen off |
| Android notification access (optional) | Ignored and preferred app rules |
| Root (optional, Tab) | Only used when the normal connect call is blocked, and only after you allow it |

## Clipboard sync

Copy on one device and paste on the others, encrypted with your shared secret. The clipboard has its own method
(Bluetooth, Both, Wi-Fi; images always use Wi-Fi), set in the Mac popover or Settings, Clipboard, and on Android on Home or
Devices. Clips from other devices arrive automatically everywhere. Sending automatically: the Mac always can; the rooted
tablet uses root; the phone uses the clipboard helper or, if you prefer, the accessibility option (EarHop, Clipboard,
Send copies automatically: Root, Helper or Accessibility, one at a time). Install the free app Shizuku, start it with Wireless debugging,
turn on its Start on boot option and allow EarHop (EarHop, Clipboard shows each step): EarHop then starts the helper by
itself whenever it is not running, also after a restart. Without Shizuku, start it from the Mac with the phone connected
over USB debugging (`bash tools/phone_clip.sh`). Without the helper, send from Android with the Share clipboard tile, the
notification button or Share. Passwords marked as secret by a password manager are never sent.

* **Delivery receipts:** each device confirms every clip it puts on its clipboard. The recent list shows "Delivered to
  Mac ✓, Tablet ✓"; a device that did not confirm gets the clip again after 5 and 15 seconds, and after a minute it says
  "not delivered".
* **Skip password-like text** (off by default): when on, copies that look like a password (one word, 8 to 64 characters
  with at least three kinds of characters; never links or email addresses) are not sent automatically.
* **Exclude apps:** copies made in these apps stay on the device. On the Mac this is the app in front when you copy; on
  Android EarHop knows the app only when the accessibility method noticed the copy.

* **Files and photos:** any size, over Wi-Fi, saved in Downloads (Android: Downloads/EarHop). Mac: the ... menu on
  the clipboard card, Send files. Android: Share, **Send with EarHop**, or the direct share targets **Send to Mac**,
  **Send to Tablet** and so on.
* **Links:** share a web link to EarHop on Android and pick a device to open it there ("continue on Mac"); on the Mac,
  copy a link and use the ... menu, Open copied link on. A link that arrives on Android is a notification: tap to open.
* **History:** searchable, with pins. History stays in memory; pinned clips are saved, encrypted with your secret.

## Earbuds extras

* **Battery:** the device with the earbuds tells the others the levels, so every device shows them (and the Mac menu
  bar). Low battery alerts at 20 % and 10 % for each bud and the case, on every device (can be switched off).
* **Same volume:** when the earbuds move, the new device takes the volume the previous one used.
* **Galaxy Buds per device:** noise control and equalizer chosen on a device are applied whenever the Buds connect to it.
* **Sleep timer:** pause and let go of the earbuds after 15 to 90 minutes (Mac popover, Android Home).
* **Shared settings:** switching, clipboard, battery, speaker and sleep timer settings changed on one device change on all.

## Quick controls and automation

* **Mac:** menu bar popover (Take, Release, sleep timer, noise control for Buds, devices, clipboard), the global hotkey
  Control Option Command E, and Settings for everything else.
* **Mac automation:** links `earhop://take`, `earhop://release`, `earhop://send-clipboard`, `earhop://sleep?minutes=30`,
  `earhop://sleep-cancel`, `earhop://diagnostics` (Shortcuts: Open URLs), and AppleScript:
  `tell application "EarHop" to take earbuds` (also `release earbuds`, `send clipboard`, `start sleep timer 30`,
  `cancel sleep timer`).
* **Android:** quick settings tiles **Take**, **Release**, **Auto switching**, **Send clipboard** and **Noise control**; a
  home screen widget; long press app shortcuts.
* **Android automation (Tasker):** switch on Settings, Extras, Allow other apps to control EarHop, then send the
  broadcasts `com.earhop.logger.action.TAKE`, `RELEASE`, `SEND_CLIPBOARD`, `SEND_TEXT` (extra `text`), `SLEEP` (extra
  `minutes`) or `SLEEP_CANCEL`.

## Troubleshooting

**Status** (Mac: Settings, Status; Android: Devices, Status) shows whether each link really works right now: Bluetooth LE
advertising and scan, the Bluetooth clipboard service, the Wi-Fi link and clipboard port, and for every device when and
over which link it was last heard, its Wi-Fi address and whether its clock is in step. **Copy diagnostics** there asks
every device for its status and the end of its log and puts everything in one file (Mac: in Downloads; Android: the share
sheet). Logs never contain clipboard contents.

Both apps write the same kinds of log lines. Mac: `~/.earhop/earhop.log` or **Copy log**. Android: **Copy log** in the
Activity card, or `adb shell cat /sdcard/Android/data/com.earhop.logger/files/earhop.log` from a computer.

| Log line | Meaning |
| --- | --- |
| `sm: a -> b dev=... ear=both/buds-rfcomm/high peers=[...]` | Every state change with everything the decision was based on |
| `hold: not taking the Buds: phone is still playing` | Why this device is playing but not switching |
| `claim: waited 1.2 s for mac to let go` | The new device waited for the old one to release before connecting |
| `release: Buds disconnected (...) in 0.8 s` | How long letting go took |
| `info: Claim failed (...): mac is still connected to the Buds` | Why a switch failed, with the next step |
| `media: explicit pause sent`, `media: explicit commands work on this Mac` | How the Mac pauses and plays |
| `buds: frame checksums verified` | The Galaxy Buds status channel is healthy |
| `link: phone is at 192.0.2.20` | A peer was heard and is now also reached directly |
| `system: woke up`, `network: Wi-Fi` | Sleep and network changes, which explain peers going quiet |

Common problems:

* **Peers never appear:** the router may isolate Wi-Fi clients. Bluetooth LE runs alongside Wi-Fi all the time (switch it in the popover Connection card or Settings, Connection), so devices in Bluetooth range still find each other. Every device needs its own name and EarHop 1.3 or later.
* **Android stops after a while:** set battery to No restrictions and allow autostart (some phones need it).
* **Galaxy Buds move by themselves:** turn off Quick Connect (Mac Settings, Buds) so Samsung's own switching does not fight EarHop.
* **Buds status says refused:** Galaxy Wearable may hold the status channel. EarHop backs off (2 s up to 60 s) and switching still works without it.
* **Safe mode:** read the `Claim failed` lines for the reason, fix it, then press Resume.

## Repository layout

```
mac/Sources/EarHop/   HandoffCore (state machine), Controller (runs it), SimTests (simulator), UI, Bluetooth, network
android/app/src/main/kotlin/com/earhop/logger/   HandoffCore, HandoffController, EarService, UI, Bluetooth, network
android/app/src/test/kotlin/com/earhop/logger/   HandoffSimTest, EarPolicyTest, BudsProtocolTest, AutoGateTest
windows/EarHop.Core/  HandoffCore, protocol, clipboard codec, Wi-Fi link (any OS); EarHop.Tests runs them on the Mac
windows/EarHop/       the Windows tray app: Core Audio, Bluetooth audio connect, media control, clipboard, UI
tools/earhop_tool.py  protocol reference: selftest, listen, send
tests/run.sh          runs every automated test
HANDOFF.md            engineering notes, history, open problems
CHANGELOG.md          what changed and what informed it
```
