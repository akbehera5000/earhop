# EarHop handoff and debugging brief

Written for another engineer (or model) to take over. It describes what EarHop is, how it is built, what has been tried, what is known to be broken, and how to debug it. Everything marked "untested" has not been run on real hardware. Read section 9 first if you only have a few minutes.

Style rules from the user: no em dashes anywhere (chat or files), the code is public on GitHub by the owner's choice (2026-10-29), but never publish secrets, Bluetooth addresses, device serials or personal details, ask before big work, be honest about what was and was not tested.

## 1. What EarHop is

One person, one pair of earbuds, three devices. The earbuds should follow the user automatically and pause and resume on ear removal.

- Mac (Apple silicon) (Swift menu bar app)
- Android phone, the device that receives calls
- An Android tablet, rooted
- Earbuds: AirPods Pro 2 (model id 0x2024) and, since later, Samsung Galaxy Buds2 Pro (its Bluetooth address is in `~/.earhop/config.json`, not in this repository)

Apple's auto switching does not work with Android, and Galaxy Buds only switch among Samsung devices, so EarHop adds this over the local network. No cloud, no accounts.

Key behaviour wanted:
- Play on a device, and the earbuds connect there. The device that started playing most recently wins.
- Old owner pauses and lets go. New owner connects.
- Pause when an earbud is removed, resume when it goes back in.
- A device receiving a call gets priority (mostly the phone).
- Wi-Fi UDP is primary, Bluetooth LE is the fallback, mDNS/Bonjour discovery.

## 2. Repo layout

Root: `~/Desktop/EarHop/earhop` (not a git repository).

```
HANDOFF.md                this file
tools/earhop_tool.py      Python selftest, listen, send (protocol reference implementation)
tests/run.sh              runs python selftest and the Swift --selftest
mac/                      Swift Package (macOS 13+, tools 5.9)
  build_app.sh            builds, bundles, signs ~/Applications/EarHop.app
  Sources/EarHop/*.swift
android/                  Gradle project (AGP 8.5.2, Kotlin 1.9.24, compileSdk 34, minSdk 26)
  app/src/main/kotlin/com/earhop/logger/*.kt
  app/src/test/kotlin/com/earhop/logger/*.kt   (JUnit, 29 tests)
dist/earhop new.apk       latest Android build (the user installs this on the phone and the Tab)
dist/EarHop-logger-debug.apk   old build, ignore
```

Runtime files on the Mac: `~/.earhop/config.json` (settings and the shared secret, mode 0600), `~/.earhop/earhop.log` (the log; the main debugging source).

### Mac source files (mac/Sources/EarHop)
- `main.swift`: AppDelegate, 0.25 s tick loop, status send (on change or 1 s heartbeat), transport failover, `--selftest`.
- `Controller.swift`: the handoff rule, call priority, pause and resume policy, claim and release actions, peers.
- `Config.swift`: Config (Codable, tolerant decoder), Log (file plus `Log.tail`, `copyToPasteboard`).
- `Protocol.swift`: Status, Proto v1/v2 (HMAC), BLE payload.
- `Link.swift`, `Discovery.swift`, `BleLink.swift`: UDP, Bonjour, BLE fallback transport.
- `BlueUtil.swift`: wrapper for the `blueutil` CLI plus instant `IOBluetoothDevice.isConnected()` check.
- `Beacon.swift`, `EarMonitor.swift`: AirPods proximity beacon parsing and scanning (CoreBluetooth).
- `BudsWear.swift`: Galaxy Buds control channel over IOBluetooth RFCOMM. Also contains `BudsProtocol` (framing, state, commands, find my earbuds safety).
- `AudioMonitor.swift`: playing detection (CoreAudio), per-process outputs, the Play/Pause media key (CGEvent), Accessibility permission helpers.
- `AppState.swift`, `PopoverView.swift`, `SettingsView.swift`, `Glass.swift`, `Support.swift` (Notifier, hotkey, QR, AutoGate, Wi-Fi name).
- `Info.plist`: Bluetooth, local network, Bonjour keys.

### Android source files (android/.../logger)
- `MainActivity.kt`: whole UI built in code (glass style), permissions, rules, Buds controls card, onboarding, history, tiles hint.
- `EarService.kt`: foreground service, 250 ms loop, owns UdpLink, BleLink, EarScanner, AacpClient, BudsClient, HandoffController.
- `HandoffController.kt`: the same handoff rule as the Mac, pause and resume, claim and release workers.
- `EarPolicy.kt` (pure, unit tested): pause and resume rules on ear removal.
- `EarScanner.kt`: AirPods beacon scan, per source tracking. `AacpClient.kt`: experimental AirPods direct channel (refused on non-root, gives up).
- `Claimer.kt`: connect and disconnect via hidden `BluetoothA2dp.connect/disconnect` by reflection, optional root fallback.
- `RootShell.kt`, `RootBt.kt`: root helper using `su` and `app_process`.
- `BudsProtocol.kt` (pure, unit tested), `BudsClient.kt`: Galaxy Buds control channel (RFCOMM, UUID 2e73a4ad-332d-41fc-90e2-16bef06523f2).
- `Tiles.kt` (quick settings tiles and `TileActions`), `Widget.kt` (home screen widget, receiver, shortcut activity).
- `Rules.kt` (RuleStore, AppRules, EarMediaListener), `AutoGate.kt` (pure, unit tested), `Earbuds.kt` (active profile), `Proto.kt`, `UdpLink.kt`, `BleLink.kt`, `EhLog.kt` (EhLog, EhState, PeerState, history), `EarHopApp.kt` (crash recorder).

## 3. Wire protocol (identical in Swift, Kotlin, Python)

UDP broadcast and unicast, port 47474, one JSON datagram, about 1 per second and immediately on change. Signed with HMAC-SHA256 using a shared secret (Mac Settings > Network shows it and a QR code; the Android app takes it by pasting or by the `earhop://pair?secret=...` link).

Canonical string v1: `1|dev|ts|seq|playing|ear|owner`. v2 adds `|call`. Test vector: secret `test-secret`, canonical `1|mac|1700000000000|7|1|both|0`, hmac `389cb0b6b95b236ec9f8ee2a76263d87cf0f876d590b14c976960cbe424b4c00`. Packets rejected on bad JSON, bad signature, or `abs(now-ts) > 30000`. Replay protection by seq and ts. Device names must be `mac`, `phone`, `tab` (BLE fallback needs these exact names).

Fields: `dev`, `ts`, `seq`, `playing` (0/1), `ear` (both, one, none, unknown), `owner` (0/1), `call` (0/1).

Per peer the receiver tracks `playingSince` and `lastPlayingAt` from when it first saw playing=1 (receive time, not sender time).

BLE fallback: 16 byte payload in a service UUID (magic 0xEA, dev code, seq, flags, 8 byte truncated HMAC over bytes 0..4 and 13..15, a signed minute stamp in 13..15; refused when more than 2 minutes off). Since 2026-10-28 both links run together by default (no failover); see item 28 in section 7.

## 4. The handoff rule (as implemented now)

Since 2026-10-27 the rule lives in one pure state machine per platform, kept line for line identical: `HandoffCore.swift` and `HandoffCore.kt`. It takes one snapshot per tick (playing, an `EarReading` with source and confidence, owner, call, peers, auto gate) and returns actions (claim, release, pause, resume, info, safe mode). `Controller.swift` and `HandoffController.kt` only carry out those actions (Bluetooth, media keys, stages for the UI, logging). States: idle, ownerIdle, playingWithoutEarbuds, claimPending, ownerPlaying, ownerPausedByEarRemoval, releasePending, lostOwnership, callPriority, safeMode. Every state change logs an `sm:` line with all inputs; `hold:` lines say why a playing device is not claiming. The deterministic simulators (`SimTests.swift`, `HandoffSimTest.kt`) replay the brief's timelines; change the rule only with a simulator test.

Additions on top of the rule below: no second pause within 6 s of EarHop's own pause or 15 s of its own release; an idle owner only releases for playback that started after it became the owner; automatic claims wait up to 8 s for the owner peer to let go; failed claims back off 2, 4, 8 s; claimBlocked clears after 2 s of quiet counted from 4 s after the block; only medium or high confidence ear readings pause or resume; resume trusts EarHop's own pause for 3 s.

The original description, still accurate in its basics:

Inputs per device each tick: `playing`, `ear`, `owner`, `inCall`, fresh peers (seen in last 5 s).

CLAIM when all true: not owner, cooled down (2 s), not busy, not in safe mode, `claimBlocked` false, no auto-gate hold, owner peer not busy, not outranked, ear is both or one (or unknown on Android when requireEar is off), and local playing for at least `need` (1 s Android, max(debounce 0.5, minPlay 1) on Mac).

- `ownerPeerBusy`: a peer that owns the earbuds and either is playing and started before us (newest playback wins, 1 s margin), or was playing within `quietSeconds` (2 s).
- `outranked`: a non-owner peer with higher priority (config order, default mac, tab, phone) is playing and ear not none, AND started within 2.5 s of our start (priority only breaks a tie; this was changed late, see section 7).
- `claimBlocked` (added late): set when this device releases the earbuds or loses them while playing. Cleared once this device has been not playing for 1.5 s. Stops it grabbing the earbuds back while its audio is stale or still running.
- Auto gate (`AutoGate`): quiet hours, Wi-Fi name list, Do Not Disturb (Android only). Holds automatic claims only. Manual Take always works.

RELEASE when this device owns the earbuds and:
- out of ears for `releaseAfterOutSeconds` (120 s), or
- another device started playing after us (`playingSince > ours + 1 s`, peer playing for 0.4 s): pause local playback first, then release, or
- this device is idle (quiet 2 s) and another device has been playing 0.4 s.

PAUSE on loss: if owner flips true to false while playing, send pause and set `claimBlocked`.

CALL: a peer with `call=1` makes the owner pause and release. A local call (Android: AudioManager mode in call or communication; Mac: microphone in use) claims if not owner.

SAFE MODE: after 3 failed claims in a row auto claiming stops until the user resumes it.

Claim worker: connect (Mac: `blueutil --connect`; Android: hidden A2DP `connect` by reflection), then poll ownership every 150 ms for 6 s (Mac Buds profile 15 s), retry once.

Ear pause and resume:
- Mac `Controller.tick`: on a transition from not-out to out while `playing` or played within 6 s: arm `pausedByUs`, remember `pausedFromNone` and `pausedByKey` (whether we sent the key). If playing, send the Play/Pause media key. Resume when ears are back in (none-case: one bud back is enough; one-bud case: both needed), within `resumeWithinSeconds` (120 s): resume if `!playing` or we sent the pause key ourselves.
- Android `EarPolicy`: same idea, unit tested (13 tests). Android pauses with a media key plus a 1.5 s audio focus grab, resumes with KEYCODE_MEDIA_PLAY then a PLAY_PAUSE retry if nothing plays after 1.5 s.
- "Pause when one earbud is out" is a per-device setting (default on).

## 5. Earbud state sources

AirPods Pro 2: proximity beacon (manufacturer data, Apple 0x004C, type 0x07, payload `07 len 01 model(LE) status battery case ...`; status bits 0x02 and 0x08 are the two in-ear flags). Findings on the phone: the primary bud shows removal immediately, the secondary bud removal arrives 24 to 33 s late or never, and the direct channel (AACP L2CAP 0x1001) is refused for non-root apps. So one-bud detection on Android for AirPods is unreliable; both-out is reliable. On the Mac the beacon works well.

Galaxy Buds2 Pro: no beacon. Wear status comes from Samsung's serial channel (RFCOMM, UUID 2e73a4ad-332d-41fc-90e2-16bef06523f2) only while the Buds are connected to that device. Framing: 0xFD, 2 length bytes little endian (low 10 bits = id + payload + 2 crc), id, payload, CRC16 XModem little endian, 0xDD. Extended status id 0x61: payload[2],[3] battery L/R, [6] placement nibbles (left high, right low; 1 wearing, 2 idle, 3 or 4 case, 0 disconnected), [7] case battery, [9] equalizer, [10] touch bits, [12] noise (0 off, 1 anc, 2 ambient), [19] quick connect (0 = on), [35] spatial audio (revision >= 1). Status id 0x60 uses offsets shifted by one. Commands: 120 noise, 134 equalizer, 124 spatial, 144 touch lock, 175 quick connect (inverted), 160/161 find start/stop, 162 mute. Offsets follow the MIT licensed project https://github.com/akafrmn/omarchy-galaxy-buds-pro (GalaxyBudsClient is GPL, facts only were used). Working on the Mac: the channel opened and reported one, none and both correctly in the log. Controls (noise, EQ, find) are implemented but untested on hardware.

Mac with Buds: if the channel is not open, ear is assumed `both`. The channel closes when the Buds go into the case or leave the Mac; the last known state is kept 20 s.

## 6. Known platform facts and constraints

- Android hidden `BluetoothA2dp.connect/disconnect` works on the phone, probably blocked on the tablet; there is an optional root fallback (button greyed out when no `su` binary exists). Root path is untested on the Tab.
- Location permission is required for BLE scans on all Android versions. Battery restrictions on some phones can kill the service (needs "No restrictions").
- macOS: posting the Play/Pause media key needs the Accessibility permission (shown as "Device Control and Data Access" on recent macOS). TCC keys the grant to the code signature. The build script now creates a self-signed identity "EarHop Local Signing" so the signature is stable across rebuilds (it was ad hoc and reset every build). The new identity required one fresh grant. `tccutil reset Accessibility com.earhop.mac` was run once.
- Running `blueutil` from the Claude sandbox aborts (no Bluetooth permission); run via the app bundle. Use `EARHOP_NO_BT=1` for the self test.
- Samsung Buds2 Pro connect to one device at a time; connecting from a second device is slow and can fail until the first device releases. Samsung's "Quick connect" (auto switch) can fight EarHop and is exposed as a toggle.
- Language support (localisation) is not implemented. Instant one-bud detection on the rooted Tab was not possible.

## 7. History of significant bugs and fixes (so you do not redo them)

1. Beacon model byte order was wrong in the original brief (little endian). Added model 0x2024.
2. Claim rule let the current owner block claims. Replaced with "newest playback wins".
3. Resume window of 30 s was too short, now 120 s.
4. Gentle scanning (duty cycled scan) delayed detection, default off.
5. Audio crackle on the Mac while scanning: caused by continuous scanning; user confirmed fixed.
6. Android crashes: location permission only checked on old SDKs, service started before permissions, null notification icon. Fixed with a permission checklist and guarded start.
7. Root fallback regression: any normal connect that returned false also tried `su`, blocking the claim worker up to about 35 s. Root is now opt-in, only used when the normal call throws (blocked), plus an "always root first" option.
8. Mac Buds: ear was hardcoded `both`, so one-bud and resume never worked. Replaced by the Samsung wear channel.
9. Mac thought it was playing after pausing (the code treated an empty per-process output list as "playing"). Fixed: when macOS lists output apps, trust the list.
10. Phone never claimed the Buds because the Mac (higher priority) looked like it was still playing and `outranked` blocked the phone. Fixed: priority only breaks a tie within 2.5 s.
11. My own bug: after losing the Buds the Mac sent a second "pause again" key 1.5 s later, which restarted the music on the speakers, after which the Mac re-claimed the Buds. Removed the retry and added `claimBlocked`.
12. Safe mode message said "after 0 failed handoffs" (count was reset before the message). Fixed.
13. Accessibility permission loop: ad hoc signature changed every build, so the toggle never stuck. Fixed with the stable signing identity; Settings link updated for the renamed pane (not verified, no screenshot access in the sandbox).
14. (Opus, 2026-10-27) Double pause on the Mac: after "pause (phone started playing)" and the release, the "lost earbuds" rule saw `playing` still true (output stays busy after a pause) and sent a second Play/Pause, restarting music on the speakers (log 20:10:48, 20:11:29, 20:12:10). The restarted audio then pulled the Buds back when the phone paused. Fix: no lost-earbuds pause when EarHop released the earbuds itself (busy or within 15 s) or sent a pause within 6 s. Selftest "no second pause after losing earbuds" added. Same guard on Android.
15. (Opus) Slow Mac release: `blueutil --disconnect` took about 10 s whenever EarHop's own Buds wear channel (RFCOMM) was open, about 1 s when it was already closed. Fix: `beforeRelease` closes the wear channel and holds it closed 5 s (`BudsWear.suspend`), then `BlueUtil.disconnect` closes the link in-process with `IOBluetoothDevice.closeConnection()` and only falls back to blueutil after 3 s. The release log line now shows how long it took.
16. (Opus) Grab-back: `claimBlocked` now clears only after 2 s of quiet, counted from at least 4 s after the release (`blockedAt`), so the playing flicker while macOS moves sound from the Buds to the speakers does not count. The Mac logs "audio is still playing on this Mac after letting go" 3 s after a release if its audio did not stop.
17. (Opus) Claim waits for the owner to let go: automatic claims (both platforms) first wait up to 8 s while a fresh peer reports owner=1, then connect. Android waits 12 s per attempt for Galaxy Buds (6 s for AirPods) and skips connect if already owner. Manual Take does not wait. Log lines say Buds or AirPods according to the active profile.
18. (Opus, second pass) Explicit state machine `HandoffCore` on both platforms, with simulators. Writing them found two more bugs: a pause before a release could follow an ear removal pause within the same second (toggle restarts audio), and an idle owner released for audio that was already playing when it got the earbuds (a Quick Connect move was undone). Both fixed and covered.
19. (Opus) Mac pause and play use explicit MediaRemote commands (`MediaControl` in `AudioMonitor.swift`), checked 2.5 s later; on no effect it falls back once to the toggle key and keeps using the key. Setting: General, Pause and play with. Not verified on hardware: the framework is present on recent macOS and answers, but no command was observed acting on real playback yet.
20. (Opus) Ear readings carry source and confidence. Mac Buds: buds-rfcomm high, last-known low (20 s after the channel closes), assumed both low. Android AirPods: none high, one and both medium, direct high; a "one" must hold 1 s to replace "none".
21. (Opus) Buds frames: checksum checked (either byte order), bad frames dropped only after one frame on the connection passed; length over 600 is junk. Android closes the Buds channel before release like the Mac. Exponential reconnect back off on both.
22. (Opus) Network: unicast to the address of the last signed packet from each peer; Mac sends directed broadcasts on every interface and logs sleep, wake and network changes; Android logs network changes.
23. (Opus) UI: new Mac popover and Android dashboard (main card, switching stages, hold and failure reasons, chips, diagnostics), profile accent, Android safe mode Resume (it had no safe mode UI before), Auto switching tile, status widget, live notification text. Tiles, widget and shortcuts now go through the controller. `EarHop --snapshot <folder>` renders the popover states to PNG for layout checks.
24. (Opus) The self test no longer writes into `~/.earhop/earhop.log` (its fake decisions were mixed into the real log).
25. (Opus) Incident, 2026-10-27 21:47: the first `--snapshot` run saved its sample settings into the real `~/.earhop/config.json` (secret and both earbud addresses became "x"), so the Buds could not connect and phone packets failed the signature check. Fixed with `Config.readOnly` (set in self test and snapshot mode, verified by checksum). The addresses were restored; the old secret was not recoverable on the Mac, so a new secret was generated at 22:08 and the phone and tab must be paired again with the QR code.
26. (Opus) 2 s ping-pong after a switch (23:22 logs): two devices were named "phone" (the tablet kept the default), and the old owner's audio dropped out and restarted on the speakers when it let go, which looked like a new start. Fixed with `advertisedPlaying` (no "playing" while claim blocked), owners yield only to playback newer than their ownership, 1 s steady playback for yielding and claiming, pause on loss uses "played in the last 1.5 s", duplicate name detection on both apps, "tab" as the tablet default. Simulators model the dropout; the ping-pong test fails without the fix.
27. (Opus, 2026-10-28) Code review fixes: Mac `CallDetector` (mic and speaker together for 2 s); AirPods beacon source picking by signal strength (8 dB window) on both; Android status order filter; BLE payload v2 with a signed minute stamp in bytes 13..15 (all three implementations share the vector `ea0000071707e997e90831cf5fb05515`, so every device must run the new build for the BLE fallback), BLE sequence reset after 30 s silence and low power after 60 s; Mac hotkey handler once, log rotation; Android log file, gate cache 0.5 s, 2 s voice chat confirmation, manual result only for manual actions. Tests: Mac self test, 55 Android unit tests, Python selftest.
28. (Opus, 2026-10-28) The Mac restarted music after a release: `MediaControl` pressed the toggle key when output looked busy 6 s after an explicit pause, but browsers keep output open after pausing. EarHop never presses the key on its own now. Links: Bluetooth LE and Wi-Fi together by default (`transport` setting both, ble, wifi), one shared sequence number, `PeerOrder` merge with tests. The phone reached the Mac over Bluetooth LE at 09:40 that morning (first real proof).


## 8. What is built (feature list)

Both apps: handoff, call priority, safe mode, quiet hours, Wi-Fi list, volume profile per device, device priority editor, handoff history with undo, ignored and preferred apps (Android, needs notification access), Wi-Fi/BLE/mDNS transports, onboarding wizard, theme and accent colours, accessibility labels, earbud profile switch (AirPods or Galaxy Buds2 Pro), AirPods battery and case battery, Galaxy Buds controls (noise, equalizer, 360 audio, touch, quick connect, safe find my Buds). Android only: quick settings tiles Take and Release, home screen widget, long press app shortcuts, glass UI with AMOLED black dark mode, optional root. Mac only: menu bar battery, global hotkey (Control Option Command E), Copy log button, launch at login.

## 9. Known problems right now (the reason for this brief)

The user reports the apps still do not perform properly. Most recent reports, in order:

A. (Fixes 14 to 17 applied 2026-10-27, untested on hardware.) Mac to Android switch with Buds2 Pro was slow, the Buds disconnected from the Mac, audio paused, then the Mac audio resumed without Buds, and pausing on the phone made the Buds jump back to the Mac. Fixes were applied for the Mac side (sections 7.11 and 4 `claimBlocked`, faster release at 0.4 s) but NOT confirmed by the user.
B. Android phone not claiming when the user plays. Fix applied (7.10) but not confirmed. No Android log has been seen for this since the fix. The Mac log only shows the Mac's side.
C. Auto switching between Mac and Android still unreliable; pause behaviour still not right. Treat these as open.
D. One-bud removal pause works on the Mac with Buds, but resume was unreliable before the playing detection fix; unconfirmed after.
E. Buds controls (noise, EQ, find, battery card) are brand new and completely untested on real Buds.

Suspects worth checking first:
- Race at handoff: the phone's claim timer (1 s) versus the Mac's release timer (0.4 s). The Buds may take several seconds to leave the Mac, and the phone's connect attempt can fail during that window; check Android's 6 s wait loop and the single retry.
- Peers flapping: the Mac log shows `peer: phone went quiet` then `joined` every few minutes. A fresh peer needs a packet within 5 s. If the phone goes quiet at the moment of the switch, neither device sees the other's playing flag.
- Playing detection accuracy on both platforms (Mac uses per-process output list via `kAudioProcessPropertyIsRunningOutput` on macOS 14.2+, falling back to device-running; Android uses `AudioManager.isMusicActive` and optionally media sessions).
- The Samsung wear channel on the phone and the Mac competes with Galaxy Wearable and with Quick connect.
- The Mac media key depends on Accessibility. Check the log for `media key blocked`.
- Android `claimBlocked` could keep a device blocked if `isMusicActive` stays true (some apps hold audio focus), which would also look like "not claiming".
- Samsung Quick connect on the Buds can move the Buds on its own.

## 10. How to build and test

Mac:
```
cd ~/Desktop/EarHop/earhop/mac
swift build -c release
EARHOP_NO_BT=1 .build/release/EarHop --selftest      # prints "selftest OK"
bash build_app.sh                                      # installs ~/Applications/EarHop.app
open ~/Applications/EarHop.app
```
Tools installed: blueutil (/opt/homebrew/bin/blueutil), openjdk@17, gradle, Android SDK (platform 34, build tools 34.0.0, emulator, AVD `earhop_test`).

Android:
```
cd ~/Desktop/EarHop/earhop/android
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew -q assembleDebug testDebugUnitTest
cp app/build/outputs/apk/debug/app-debug.apk "../dist/earhop new.apk"
```
Unit tests: HandoffSimTest (13), EarPolicyTest (13), AutoGateTest (4), BudsProtocolTest (18), 48 in total. Mac self test includes 13 simulator timelines, Buds frame fixtures and packet security checks. Everything: `bash tests/run.sh` (Python, Mac, Android). The emulator `earhop_test` boots headless with `emulator -avd earhop_test -no-window`; the new APK starts there without crashing (light and dark checked on 2026-10-27).

The emulator (`earhop_test`) has no Bluetooth adapter, so it only verifies UI and crashes. All handoff, Buds, root and BLE behaviour needs the real devices.

## 11. How to debug (what to ask the user for)

1. Mac log: `~/.earhop/earhop.log`, or the Copy log button (popover or Settings > Log). Look for lines starting `claim:`, `release:`, `pause:`, `resume:`, `info:`, `peer:`, `buds:`, `blueutil`, `transport:`.
2. Android log: the app's Copy log button (Activity card). Look for `handoff:`, `claim:`, `peer ... playing=`, `state:`, `buds:`, `root:`, `owner:`.
3. Ask the user to run one scenario while collecting both logs with timestamps: Buds on Mac playing, then start audio on the phone, note the wall clock time, then pause on the phone. Compare, line by line, when each side saw the other's `playing`, when the Mac released, when the phone attempted `connect`, and when ownership flipped.
4. Verify the clocks of the devices are within a few seconds (messages older than 30 s are dropped).
5. Check Mac Settings > Handoff and the Android "Automatic switching" card for stray settings (auto off, quiet hours, Wi-Fi only, ignored apps, volume profile).

The Mac config is `~/.earhop/config.json` (do not paste the `secret` anywhere). Relevant keys: `liveMode` (must be true to act), `earbuds` (airpods or buds), `budsAddress`, `airpodsAddress`, `priority`, `pauseWhenOneOut`, `ignoredApps`, `cooldownSeconds`, `debounceSeconds`, `quietSeconds`, `minPlaySeconds`.

## 12. Not done and not verified

- Everything from items 14 to 24 in section 7 is verified by tests and builds only, not on the real Mac, phone, tablet, AirPods or Buds. The first real check should be the Mac to phone and back scenario in section 11 with both logs.
- MediaRemote explicit pause and play on recent macOS (item 19). Watch for `media: explicit commands work on this Mac` or `had no effect`.
- Galaxy Buds frame checksum rule against real frames (watch for `buds: frame checksums verified`).
- The research repositories named in CLAUDE_OPUS_INTEGRATION_BRIEF.md were not consulted yet; no external code is in the project.

- Language support, Windows or Linux agents, firmware flashing (deliberately skipped), the Linux-only codec control from the Buds project.
- Instant one-bud detection for AirPods on Android (needs root and a patched Bluetooth stack, not feasible).
- BLE fallback and mDNS discovery between real devices.
- Root connect and release on the Tab.
- Buds noise, equalizer, find my Buds, battery on real hardware.
- Stable verification that the Accessibility prompt now appears and sticks after the signing change.
- Any end to end handoff reliability testing after the late fixes in section 7.
