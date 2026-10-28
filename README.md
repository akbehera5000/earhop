# EarHop

One pair of earbuds, several devices. EarHop moves your AirPods Pro 2 or Galaxy Buds2 Pro to whichever device
starts playing, pauses the device that lost them, and pauses and resumes when you take an earbud out. It runs
entirely on your local network: no cloud, no account.

| Device | App |
| --- | --- |
| Mac (macOS 13 or later, Liquid Glass on macOS 26 and later) | `mac/`, a menu bar app |
| Android phone and tablet (rooted Android tablet) | `android/`, an app with a foreground service |

## How switching works

* **Newest playback wins.** When a device starts playing, it takes the earbuds. Device priority only breaks a near tie (both started within 2.5 s).
* **The old owner pauses, then lets go.** EarHop sends one explicit Pause, never a second toggle, so music never restarts on the speakers.
* **No grabbing back.** A device that let go or lost the earbuds waits until its own audio has really stopped before it can take them again.
* **Calls first.** A device with a call gets the earbuds; the owner pauses and releases.
* **Ear removal.** Taking a bud out pauses; putting it back within 2 minutes resumes, but only if EarHop caused the pause.
* **Safe mode.** After 3 failed switches in a row, automatic switching pauses. Take and Release always work.

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
./gradlew assembleDebug testDebugUnitTest
```

Install `android/app/build/outputs/apk/debug/app-debug.apk` (also copied to `dist/earhop new.apk`) on the phone and the tablet.

All tests at once: `bash tests/run.sh`.

## Setup and pairing

1. **Mac:** open EarHop, connect your earbuds to the Mac, press **Detect** in the popover. Allow Bluetooth, Local Network and
   Accessibility when macOS asks. Accessibility is shown as *Device Control and Data Access* on newer macOS.
2. **Pair Android:** on the Mac open Settings, Network, and scan the QR code with the phone camera. EarHop opens with the
   shared secret filled in. Or copy the secret and use **Paste secret**.
3. **Name each device** `mac`, `phone` or `tab`. These exact names are required for the Bluetooth LE link.
4. **Android permissions:** Nearby devices, Location (Android needs it for Bluetooth scans), Location switched on,
   Notifications, and Battery set to *No restrictions* (essential on some phones). Notification access and root are optional.
5. Press **Start** on Android and turn on **Live** on the Mac (off is a dry run that only logs decisions).

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
tablet uses root; the phone needs the clipboard helper, started once from the Mac with the phone connected over USB
debugging (`bash tools/phone_clip.sh`), and again after the phone restarts. Without it, send from Android with the Send
clipboard tile, the notification button or Share. Passwords marked as secret by a password manager are never sent.

## Quick controls

* **Mac:** menu bar popover (Take, Release, noise control for Buds, devices, recent events, diagnostics), the global hotkey
  Control Option Command E, and Settings for everything else.
* **Android:** quick settings tiles **Take**, **Release** and **Auto switching**; a home screen widget showing where the
  earbuds are; long press app shortcuts.

## Troubleshooting

Both apps write the same kinds of log lines. Mac: `~/.earhop/earhop.log` or **Copy log**. Android: **Copy log** in the Activity card.

| Log line | Meaning |
| --- | --- |
| `sm: a -> b dev=... ear=both/buds-rfcomm/high peers=[...]` | Every state change with everything the decision was based on |
| `hold: not taking the Buds: phone is still playing` | Why this device is playing but not switching |
| `claim: waited 1.2 s for mac to let go` | The new device waited for the old one to release before connecting |
| `release: Buds disconnected (...) in 0.8 s` | How long letting go took |
| `info: Claim failed (...): mac is still connected to the Buds` | Why a switch failed, with the next step |
| `media: explicit pause sent`, `media: explicit commands work on this Mac` | How the Mac pauses and plays |
| `buds: frame checksums verified` | The Galaxy Buds status channel is healthy |
| `link: phone is at 192.168.1.20` | A peer was heard and is now also reached directly |
| `system: woke up`, `network: Wi-Fi` | Sleep and network changes, which explain peers going quiet |

Common problems:

* **Peers never appear:** the router may isolate Wi-Fi clients. Bluetooth LE runs alongside Wi-Fi all the time (switch it in the popover Connection card or Settings, Connection), so devices in Bluetooth range still find each other; device names must be `mac`, `phone`, `tab`.
* **Android stops after a while:** set battery to No restrictions and allow autostart (some phones need it).
* **Galaxy Buds move by themselves:** turn off Quick Connect (Mac Settings, Buds) so Samsung's own switching does not fight EarHop.
* **Buds status says refused:** Galaxy Wearable may hold the status channel. EarHop backs off (2 s up to 60 s) and switching still works without it.
* **Safe mode:** read the `Claim failed` lines for the reason, fix it, then press Resume.

## Repository layout

```
mac/Sources/EarHop/   HandoffCore (state machine), Controller (runs it), SimTests (simulator), UI, Bluetooth, network
android/app/src/main/kotlin/com/earhop/logger/   HandoffCore, HandoffController, EarService, UI, Bluetooth, network
android/app/src/test/kotlin/com/earhop/logger/   HandoffSimTest, EarPolicyTest, BudsProtocolTest, AutoGateTest
tools/earhop_tool.py  protocol reference: selftest, listen, send
tests/run.sh          runs every automated test
HANDOFF.md            engineering notes, history, open problems
CHANGELOG.md          what changed and what informed it
```
