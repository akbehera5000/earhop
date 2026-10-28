# Changelog

## 2026-10-29, notification labels and a single icon

* The robot icon on the phone was a cached icon: it went away after a phone restart. The extra large icon added
  for the phone is removed again, so the notification shows one icon.
* Clearer button labels: "Take Buds" (or "Release"), "Share clipboard" (send this device's clipboard to the others) and
  "Auto switch: On" or "Off" (automatic earbud switching), at 12 sp so they fit on the phone. The collapsed notification
  was checked on the phone; the expanded one with the new labels was not.

## 2026-10-29, phone notification

* The notification buttons were cut off on the phone ("Auto switching: on" wrapped to two lines). Labels are now
  "Take Buds", "Send clip" and "Auto: on", one line each with an ellipsis if needed. Checked on the phone.
* The phone shows a generic Android robot as EarHop's icon in the notification shade (the tablet shows it correctly). The app now
  also ships bitmap icons (`tools/make_android_icon.swift` draws them from the same design) and, on that phone only, a large
  icon in the notification, which shows on the right. The robot on the left stayed through a version bump (now
  versionCode 4, versionName 1.0.1) and even with bitmap-only icons, so the phone replaces the icon itself, possibly
  because it is a debug build installed over USB or because of a cached icon that a restart clears. Not solved yet.

## 2026-10-29, one Shell toast per copy

* The owner saw "Shell pasted from your clipboard" twice per copy. Likely cause: some apps write the clipboard twice for
  one copy, and the helper read each write (Android shows the toast once per read of each new clip). The helper now
  waits until the clipboard is quiet for 0.8 s and reads once, and logs every read (`read: clip from <time>`, no
  contents) in `/data/local/tmp/earhop-clip.log`. Waiting for the owner's next copy to confirm.

## 2026-10-29, earbud switching card, pauses, fewer clipboard toasts

* Mac popover: the Connection card is now "Earbud switching" (it only carries the earbud status, the clipboard has its
  own card). New in it: Auto (automatic switching on or off), Calls, Ear pause, and a pause menu (15 minutes, 1 hour,
  until tomorrow 7:00). Off or paused, `Controller.tick` makes no automatic decisions, like the Android app; Take and
  Release still work. Settings: the page is now "Earbud Link" with an Automatic switching section. `autoSwitch` is
  saved; a pause is not (a restart resumes).
* Android: an Earbud switching card on Home (Switch automatically and Pause), Pause rows for the clipboard on Home and
  Devices, and Pause in Settings, Automatic switching (`autoPausedUntil`, `clipPausedUntil`). The Devices card
  "Connection" is now "Earbud switching link".
* "Shell pasted from your clipboard": Android shows it whenever the helper reads a clip. The helper now checks the clip's
  description first and skips clips EarHop itself put there (label EarHop), sensitive ones, and unchanged ones, so it
  reads only your own new copies. Not verified: the phone was locked during testing, and Android blocks every clipboard
  read (description included) while locked. The one toast per own copy remains unless "Show clipboard access" is turned
  off in Android's privacy settings.
* The helper log (`/data/local/tmp/earhop-clip.log`) notes changes with sizes only, never contents.

## 2026-10-29, Mac clipboard card and settings

* The popover screenshot showed the old build still running: `build_app.sh` installed into /Applications but left the
  running copy alone. It now restarts EarHop after installing.
* Popover Clipboard card: on/off switch, its own Bluetooth / Both / Wi-Fi switch (same style as Connection), a live dot
  per link with how many devices it reaches, the last three clips (click to copy again), Send now, an Auto switch and a
  Pause menu (15 minutes, 1 hour, until tomorrow 7:00).
* Settings, Clipboard: the method with live dots, Send copies automatically, Receive clips, Include images, Notify me,
  Send to per device, Recent clips (off, 5, 10, 20; memory only, never saved), Pause and Resume, Send clipboard now.
* Checked with `--snapshot` renders (light and dark) and the self test. The live popover was not checked by eye after
  the restart.

## 2026-10-29, clipboard helper for the phone, clipboard settings

* Root cause of "copying on the phone never syncs": checked with adb, the phone (Android 16) never writes
  "Denying clipboard access" to the system log, so the log watch could never fire there, with or without log access.
  New `ClipHelper`: started once from the Mac (`tools/phone_clip.sh`), it runs as the shell user, which Android lets
  read the clipboard in the background, and passes each new text to the app through `ClipHelperProvider`
  (`content call`; the provider accepts only the shell user and root). A first version used a local socket, which
  SELinux denied. Tested on the phone: a copy was noticed, sent once over Bluetooth and Wi-Fi, and arrived on the Mac.
  The helper must be started again after the phone restarts. Text over 100 KB through the helper is not tested.
* Found while testing: with EarHop open on screen, its clipboard listener re-sent the same clip about every second
  (the phone reports a change whenever the clipboard is read). The listener now skips clips already sent or received.
* Installing a new APK stopped EarHop until Start was pressed. `RestartReceiver` now starts it after an update or a
  phone restart unless it was stopped on purpose (tested after an update on the phone).
* Clipboard settings on both apps: its own method (Bluetooth, Both, Wi-Fi), separate from the handoff connection;
  Receive clips; Send to per device; on the Mac also Send copies automatically and Notify when a clip arrives. Mac: a
  Clipboard card in the popover (switch, method, last event, Send now) and a Settings, Clipboard page. Android: a
  Clipboard card on Home and the full card on Devices. The settings UI is built and installed but not checked by eye.

## 2026-10-29, phone clipboard: log access and big clips

* Phone log 00:26 to 00:29: the log watch caught a copy once (00:27:46), then nothing after a restart at 00:28:27.
  Android 13 and later show other apps' log lines only after "Allow access to all device logs?", and ask only while the app
  is on screen; a watch started in the background silently sees only EarHop's own lines. EarHop now checks this a few
  seconds after the watch starts (`clip: log access works` or a warning), restarts an unconfirmed watch whenever EarHop is
  opened so Android can ask, and shows "Allow log access when Android asks" with an Ask again button. Each detected copy
  logs `clip: copy noticed`.
* The 96 KB clip at 00:27:46 was not sent: over 64 KB cannot go over Bluetooth and "Bluetooth only" ruled out Wi-Fi. Both
  apps now use Wi-Fi for clips Bluetooth cannot carry (images, text over 64 KB) even in "Bluetooth only", with addresses
  from Bonjour discovery as well as from status packets.

## 2026-10-28, automatic clipboard sending without root

* The phone (not rooted) could only send copies by hand, because Android blocks background clipboard access. EarHop now
  also watches the system log without root when it has two one-time permissions: READ_LOGS (granted once with
  `adb shell pm grant com.earhop.logger android.permission.READ_LOGS`) and "Display over other apps" (Settings), which lets
  it open the invisible send screen from the background. Android 13 and later may ask to allow log access when EarHop
  starts. Devices, Clipboard, Send copies automatically shows both steps, with a button to copy the command and one to
  open the setting, and starts automatic sending once both are done. Rooted devices keep using root.

## 2026-10-28, clipboard reaching the tablet

* Every Bluetooth clipboard send to the tablet timed out while the phone worked (Mac log 10:54 to 11:07, all devices on
  "Bluetooth only"). Likely cause: Android restarted the status advertisement every 5 s and the tablet takes a new
  random Bluetooth address on each restart, so connections to it never completed. Android now restarts the advertisement
  only when the status changes (or once a minute).
* Mac and Android retry a failed Bluetooth clipboard send once with the device's newest address, and log each step
  ("failed while connecting", "while finding the clipboard service", "while writing") to show where a send stops.

## 2026-10-28, automatic clipboard sending on the rooted tablet

* Android 13 does not tell a background app that the clipboard changed, so automatic sending did not work on the Galaxy
  tablet (Android 13). On rooted devices EarHop now reads the system log as root, where Android records that it refused
  EarHop clipboard access each time something is copied, then opens its invisible send screen as root, which reads the
  clipboard with focus and sends it. Silent, debounced, and clips that just arrived from another device are not sent back.
  Switch: Devices, Clipboard, Send automatically (root). Not yet verified on the tablet, which may word the log line
  differently; the first matching lines are copied into the EarHop log (`clip: system log: ...`) to check.

## 2026-10-28, clipboard sync

* Copy on one device, paste on the others. Text goes over Bluetooth LE and Wi-Fi at the same time (the first copy to
  arrive wins, the other is recognised by its id); images go over Wi-Fi only (up to 10 MB). Every clip is encrypted with
  AES-256-GCM using a key derived from the shared secret, and clips older than 5 minutes are refused.
* Bluetooth LE: each device runs a small GATT service; the sender connects for a moment, writes 180 byte chunks and
  disconnects (text up to 64 KB). Status advertising on Android is now connectable for this. Wi-Fi: TCP port 47475.
* Mac (`Clipboard.swift`): watches the clipboard, never sends items password managers mark as concealed or transient.
  Settings, Connection, Clipboard; the popover shows the last clip.
* Android (`ClipCodec.kt`, `ClipSync.kt`): clips from the Mac arrive automatically. Android 10 and later block background
  clipboard reading, so sending uses the Send clipboard tile, the notification button, Share, Send to my devices, or the
  Send clipboard now button (automatic if Android allows it). Items marked sensitive are never sent. Images are shared to
  other apps through a small file provider.
* Tests: both apps read each other's encrypted sample clip, refuse a wrong secret and tampering, reassemble chunks out of
  order, and pass a Wi-Fi loopback test. Bluetooth LE between real devices is not yet verified.
* Android: a solid strip behind the status bar so scrolled content no longer runs under the clock.

## 2026-10-28, Android redesign, notification and tiles

* Real app icon: an adaptive icon (blue to violet tile, white earbuds and a swap arrow, monochrome layer for themed icons)
  replaces the plain vector that the phone showed as the Android robot. New white notification icon and tile icons.
* Notification: collapsed it shows the earbuds, where they are and what is happening, with battery on the right. Expanded it
  adds battery bars (left, right, case), Off / Noise cancelling / Ambient pills for Galaxy Buds on this device, Take or Release,
  and Auto switching on or off. The buttons work without opening the app (`NotifUi.kt`, `EarActionReceiver`).
* Quick settings tiles show live state (where the earbuds are, battery, "Not on this device"); new Noise control tile cycles
  Off, Noise cancelling, Ambient. The service refreshes the tiles when the state changes.
* App: five pages (Home, Buds, Devices, Settings, Activity) behind a floating pill tab bar; iOS style grouped sections with
  titles above rounded cards; system grey and black backgrounds; real switches instead of "ON (tap to turn off)" buttons;
  segmented controls for earbuds, connection mode and theme; battery tiles in the Buds controls; quick noise control on Home;
  the status pill on Home starts and stops EarHop. Checked on the emulator in light and dark mode.

## 2026-10-28, connection mode switch on the Mac

* The popover has a Connection card: a three way switch (Bluetooth, Both, Wi-Fi) with a live dot per link (green working,
  orange nobody heard, grey off). Settings has a new Connection page with the same switch, what each mode means, when each
  device was last heard and over which link, and a warning if the Mac's name blocks Bluetooth LE.

## 2026-10-28, Bluetooth LE first, and no more music restarting on the Mac

* Fixed: after the Mac let go of the earbuds, the music paused and then started again on the speakers by itself. The pause
  worked, but browsers keep their audio output open after pausing, so 6 s later EarHop thought the audio was still playing
  and pressed the Play/Pause key, which restarted it (logs 08:58, 08:59, 09:38, 09:40 on 2026-10-28, "apps: helper").
  EarHop now never presses the toggle key on its own; only explicit Pause and Play commands are sent (the key only in the
  "Play/Pause key only" setting). The Accessibility banner shows only in that mode.
* Links: Bluetooth LE is now the main link and Wi-Fi runs alongside it. Every status goes out over both at once and the
  receiver keeps the newest copy, so a failing link costs nothing and there is no 10 s failover gap. Settings: "Bluetooth LE
  and Wi-Fi" (default), "Bluetooth LE only" (for testing), "Wi-Fi only". The status shows which links bring news right now.
* Both links carry one sequence number (Bluetooth LE its low 16 bits); `PeerOrder` (Swift and Kotlin, with tests) keeps only
  newer copies, treats repeats as "still here", and accepts a restarted device. Android merges peers under a lock.
* Android advertising is Balanced (Low power after a minute with no Bluetooth peer), scanning Low latency (Balanced in low
  power). The Mac no longer advertises in bursts.
* First real evidence: on 2026-10-28 09:40 the phone's status reached the Mac over Bluetooth LE.

## 2026-10-28, installable Mac app (1.0.0)

* `mac/make_dmg.sh` builds `dist/EarHop-1.0.0.dmg`: a universal (Apple silicon and Intel) EarHop.app with an icon, version
  1.0.0, minimum macOS 13, an Applications shortcut and an install guide. `build_app.sh` and the DMG share `package.sh`.
* The Mac connects the earbuds itself through macOS Bluetooth; Homebrew `blueutil` is no longer required and is only used as a
  second try when installed. The paired device list comes from macOS. (In-app connect is not yet verified on the real Buds.)
* Signed with the local "EarHop Local Signing" certificate; not notarized (needs an Apple Developer ID).

## 2026-10-28, code review fixes

* Mac: a call needs the microphone and the speaker busy together for 2 s (ends 2 s after the microphone is free). Dictation or an
  idle open microphone no longer takes the earbuds from the phone (`CallDetector`, with self tests).
* AirPods (both apps): only beacons within 8 dB of the strongest smoothed signal count, so someone else's AirPods nearby cannot
  pause or resume you; battery is read from the strongest source (`Beacon.pick`, `BeaconPick.pick`, with tests).
* Android drops duplicate and late copies of a status (same rule as the Mac: no newer sequence number and timestamp).
* Bluetooth LE fallback (both apps and tools/earhop_tool.py): bytes 13..15 now carry a signed minute stamp, so a recorded
  advertisement is refused after 2 minutes (new shared vector `ea0000071707e997e90831cf5fb05515`; older builds cannot read it).
  A peer's sequence number is forgotten after 30 s of silence, so a restarted device is heard at once. With no device heard for
  a minute the fallback goes to low power (Android low power scan and advertising, Mac 3 s advertising bursts every 30 s).
* Mac: the global hotkey handler is installed once (toggling the setting no longer makes one press take and release); the log
  rotates at 2 MB to earhop.log.1 and Copy log reads across the rotation; earbud wording follows the active pair.
* Android: the log is also written to files/earhop.log (rotated at 1 MB) and Copy log reads it, so it survives restarts and
  crashes; the Wi-Fi name and Do Not Disturb are checked twice a second (0.5 s cache) instead of four times; voice chat audio
  mode counts as a call only after 2 s (ringing and phone calls still at once); Take and Release report only their own result;
  the detection card has Galaxy Buds text and hides the AirPods only scanning option in Buds mode.

## 2026-10-27, no more 2 second ping-pong (reliability over speed)

* Cause found in the logs: the tablet was also named "phone", so the Mac saw one "phone" flick between playing and stopped every
  second; and when the Mac let go, its audio dropped out and restarted on the speakers, which the phone took as "Mac started playing"
  and handed the Buds back after about 2 s.
* A device that just let go or lost the earbuds no longer announces "playing" until its audio has really stopped.
* An owner only yields to playback that started after it got the earbuds, and only after 1 s of steady playback (was 0.4 s).
* Playback must last 1 s again before claiming (Mac and Android), reverting the 0.6 s speed change.
* When the earbuds leave unexpectedly, "playing in the last 1.5 s" counts for the pause, because the output drops out at that moment.
* Both apps detect two devices using one name and say so (Mac banner, Android dashboard, log). Tablets now default to the name "tab".
* New addresses of a peer are logged once instead of on every packet.
* Simulators now model the audio dropout; new tests for the ping-pong and a flickering peer fail on the old rules and pass now.

## 2026-10-27, pairing problems are visible

* Both apps report a device whose packets are refused because of a different secret or a clock more than 30 s off: a log line
  (`link: packets from phone refused: ...`), a banner in the Mac popover with a Pair button, and a note on the Android dashboard.
  The packets themselves are still never accepted. Self tests cover both reasons.
* Mac: when the audio is really still playing 6 s after an explicit pause (or still stopped after a play), the Play/Pause key is
  sent once. Pause lines now name the app producing the sound.

## 2026-10-27, faster switching

* Mac claims no longer wait for `blueutil --connect` to exit; EarHop watches the link every 0.1 s and moves on the moment it is up
  (blueutil kept running about 0.8 s after the Buds were connected). The log line `claim: link up X s after connect started` shows it.
* Playback must last 0.6 s instead of 1 s before a device claims (Mac default and Android). Mac setting: Handoff, Ignore sounds shorter than.
* Explicit pause and play are judged failed only after 6 s without effect, and never when a newer command was sent. The earlier
  2.5 s check could mistake stale audio output for a failed pause and press the toggle key, which may restart the music.

## 2026-10-27, reliability and redesign (Claude Opus)

Everything below passes the automated tests. None of it has been verified on the real devices yet; see HANDOFF.md section 9.

### Handoff safety
* New `HandoffCore` (Swift and Kotlin, line for line the same rules): an explicit state machine with the nine states from the
  integration brief, a pure function of one snapshot per tick, so every decision can be replayed in tests.
* Fixed the double pause: after EarHop paused or let go, the "lost the earbuds" rule pressed Play/Pause a second time and
  restarted music on the Mac speakers (seen in the Mac log on 2026-10-27 at 20:10:48, 20:11:29 and 20:12:10). The same guard now
  covers a pause before a release, so an earbud removal during a handoff also gives one pause only.
* Mac: explicit Pause and Play commands through the system media service instead of the Play/Pause toggle, verified after
  each use; the key is used once as a fallback only if the explicit command had no effect.
* Fixed grab back: an idle owner only lets go for playback that started after it got the earbuds, so a Quick Connect move or a
  manual take is not undone by audio that was already playing elsewhere.
* Claim blocking after a release now needs 2 s of real quiet, counted from 4 s after the release, so the playing flicker while
  sound moves to the speakers does not count as a new start.
* Automatic claims wait up to 8 s for the current owner to let go before connecting; Galaxy Buds get 12 s per attempt.
* Failed claims back off (2, 4, 8 s) before safe mode, so they can never loop quickly.
* A call on Android claims even when no music plays; the Mac still needs playback with the microphone (dictation is not a call).
* Every decision explains itself: `sm:` transition lines with all inputs, `hold:` lines saying why a device is not switching, and
  failure reasons with the next step.

### Ear detection
* Ear readings carry a source (`buds-rfcomm`, `airpods-beacon`, `airpods-direct`, `last-known`, `assumed`) and a confidence.
  Only medium or high confidence readings pause or resume.
* Android AirPods beacon: "none" is high confidence; a later "one" must hold for 1 s before replacing "none", because the second
  bud often reports late.
* Resume right after EarHop's own pause trusts the pause for 3 s even if the output still looks busy (both platforms).

### Galaxy Buds channel
* The status channel is closed before EarHop disconnects the Buds (the open channel made the Mac disconnect take about 10 s);
  in-process disconnect on the Mac with blueutil as a fallback.
* Frame checksums are checked. Bad frames are dropped only after one frame on the connection has passed, so a wrong checksum
  assumption cannot silence the Buds. Lengths over 600 bytes are treated as junk instead of stalling the parser.
* Exponential reconnect back off (2 s up to 60 s) on both platforms; a stale status (2 minutes) is no longer trusted.

### Network
* Peers are also reached directly at the address their last signed packet came from, and the Mac sends a subnet broadcast on
  every active interface. Unsigned packets are never accepted.
* Mac logs sleep, wake and network changes and announces its state at once afterwards; Android logs network changes.

### Interface
* Mac popover redesigned: one main card (earbuds, where they are, Playing, Paused, Ready or Switching), a four stage switching
  bar, why it is not switching, the last failure, battery and case chips, large Take and Release buttons, devices, recent
  events, and a Diagnostics section. The accent follows the earbuds (blue for AirPods, violet for Galaxy Buds) unless you
  pick a colour. The menu bar icon shows the earbuds, switching and safe mode.
* Mac Settings: a "Now" section on the Handoff page, and Permissions and control (Accessibility, Bluetooth, Local network,
  pause and play method) on the General page.
* Android: a matching dashboard card with switching stages, failure and hold reasons, chips, a safe mode Resume button (safe
  mode was not visible before) and Diagnostics. The notification shows the live state. New Auto switching tile. The home screen
  widget shows where the earbuds are. Take and Release from tiles, widget and shortcuts now go through the handoff controller.

### Tests
* Mac self test: 13 simulator timelines from the brief (Mac to phone, phone to Mac, pause then start within 2 s, stuck playing
  owner, 7 s disconnect, silent holder and safe mode, call, manual take while gated, removal during handoff, Quick Connect,
  ear pause and resume, low confidence, near tie), Buds frame fixtures, bad HMAC, stale and replayed packets.
* Android: `HandoffSimTest` (13 tests, the same timelines) and 6 new Buds frame tests. 48 tests in total.
* `tests/run.sh` now runs Python, Mac and Android tests.

### Sources
No external code was copied in this change. The Galaxy Buds frame layout and status offsets were taken earlier from the
MIT licensed omarchy-galaxy-buds-pro project (facts only; see HANDOFF.md section 5). The other repositories listed in the
integration brief (LibrePods, CAPod, OpenPods, GalaxyBudsClient, LiveBudsCli, MagicPodsCore, Budsy and others) were not
consulted for this change. Comparing the Buds offsets against them, and against frames captured from the real Buds, is still open.
