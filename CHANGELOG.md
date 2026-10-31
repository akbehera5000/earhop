# Changelog

## 2026-10-31, speaker fallback (Mac and Android 1.2.4)

* New on both: with the earbuds connected but out of your ears, audio you start plays on the device's own speakers, and
  moves back to the earbuds, without stopping, as soon as you put one in. Audio already playing when you take the
  earbuds out is still paused (not moved). Calls are left alone. On by default: "Use the speaker(s) when earbuds are
  out" (Android: Buds, In-ear detection; Mac: Settings). With it on, a device keeps the earbuds while they are out
  instead of letting go after 2 minutes, so it notices when you put them back in.
* Mac: switches the system sound output between the built-in speakers and the earbuds (CoreAudio); the popover says
  "On speakers". Switches back to the earbuds if EarHop quits while on the speakers. Tested: out and back on this Mac.
* Android: changes the active audio device through the hidden A2DP call (none = speaker, the earbuds = back); root is
  the last resort when switched on. Confirmed by reading where the music stream plays. Tested on the phone: music
  went bt_a2dp -> speaker -> bt_a2dp. Buds, In-ear detection has "Test speaker (5 seconds)". The card says "playing on
  the speaker" while it is.
* Unit tests (SpeakerPolicyTest, 6 cases) and Mac self test cases for the rules.
* Fixed (Mac): speaker fallback never switched. After EarHop's pause, Music and other players keep their output open, so
  the Mac still saw "playing" and never noticed you pressing play again. The Mac now moves the output to the speakers
  while it is silent, once EarHop has paused because both buds came out, so the next play is heard there; when a bud
  goes back in, the output returns to the earbuds before the resume command is sent.
* Fixed (Mac): putting the buds back in more than 3 s after the pause did not resume ("audio is already playing"), for
  the same reason. The Mac trusts its own pause until the buds are back; the resume is an explicit play command, never a
  toggle.
* Mac version is now 1.2.4 (was still 1.0.0); dist/EarHop-1.2.4.dmg and dist/earhop new.apk (1.2.4) built.

## 2026-10-31, Mac: live earbuds picture on the main card; Android 1.2.3

* Mac: the main card shows the same live picture as the phone (BudsPicture.swift): both buds and the case, worn buds
  floating with sound waves while playing here, buds in the case sitting in it, a battery ring and level per bud, the
  case level, and a bolt while charging. Uses the accent colour for rings and waves; Reduce Motion turns off bobbing
  and waves. It replaces the icon and the battery chips.
* Mac: the menu bar popover never grows past the screen: it is as tall as its content up to the screen's usable height,
  and scrolls beyond that (it used to run off the bottom with Diagnostics and the footer out of reach).
* Both: the lid stays closed when no bud is known to be out, and the levels of buds in the case sit beside the case
  instead of over the open lid.

## 2026-10-31, Mac: claims of Galaxy Buds no longer fail; Android 1.2.2: letting go drops the call link too

* Fixed (Mac): claiming Galaxy Buds always "failed" with "the Buds did not accept the connection", although macOS had
  connected them (its Bluetooth log showed HFP, AVRCP and A2DP up). On this macOS IOBluetooth's isConnected() kept
  answering false for the Buds, for the device made from the address and for the one from the paired list. The
  connection check now also looks for the earbuds' Bluetooth audio device in CoreAudio (its ID carries the address).
  Tested: the Mac took the Buds from the tablet and from the phone, opened the wear channel and read the batteries.
  The Mac's wear channel retries after 2, 4, 8 and then every 15 s instead of up to a minute.
* Android: letting go now also disconnects the call link (HFP), best effort. With only the music link dropped the
  Buds stayed tied to the phone and came back to it within 2 s (seen when the tablet tried to take them).

## 2026-10-31, Android 1.2.1: switching and Buds controls fixed, live earbuds picture

* Fixed: on Galaxy Buds the wear state turned "unknown" 2 minutes after the Buds last reported, although the channel was
  open and the Buds were in your ears (they only report on a change). Unknown wear broke automatic switching and
  pause/resume. While the channel is open the last report now stays valid.
* Fixed: noise control, touch controls, equalizer and battery disappeared for long stretches. The wear channel retried
  only every minute after a refusal and its connect could hang; it now retries after 2, 4, 8 and then every 15 s, and a
  connect that hangs is ended after 12 s. Battery levels stay shown while the channel is open (they used to vanish after
  a minute without change).
* New: the earbuds card shows a live picture of both buds and the case. Worn buds float and bob, with sound waves while
  playing; buds in the case sit in it; buds that are out rest beside it. Each bud has a battery ring and level, the
  case shows its level, and charging shows a bolt.

## 2026-10-31, Android 1.2.0: new look, production build

* New design language: an AMOLED black page with a faint dot grid, dark graphite cards, and glowing colour cards for
  what matters most (the earbuds card glows magenta while the earbuds are here, indigo while they are elsewhere, violet
  while switching; Switching and Clipboard tiles glow indigo and ember when on). Battery levels in dot-matrix digits with
  dotted level bars. Centred page titles between round buttons, white pill buttons, pill switches.
* Colours are decoration only: on and off states are shown by outlines, green for on and red-grey for off (switches,
  tiles, the Running chip, the earbuds state pill, status rings). The accent colour setting was removed.
* Production app instead of a debug build: a release APK signed with EarHop's own key, shrunk with R8 (432 KB instead
  of 1.4 MB), not debuggable. Debug output moved out of sight: raw readings, the self test and Copy diagnostics are in
  Settings, Advanced; the log and decisions are in Activity, Technical log; a crash shows a short note with Copy report
  instead of the stack trace; in-ear status is described in words; the computer setup for the clipboard helper is folded.
* New: Settings, Backup: Export and Import of all EarHop settings as a file (root permission is not carried over).
* Activity has a Timeline of every switch with its reason. Home's left round button shares the clipboard.
* One-time step: the release key differs from the debug key, so Android will not update the old build in place.
  Export the settings (or keep them with adb, see README), uninstall the old EarHop, install the new one, Import.

## 2026-10-29, Android redesign, one clipboard method at a time

* Fixed: the Accessibility setup said On while the service was off in Android settings. The status now needs the
  saved setting, Android's live list of enabled services and the running service to agree, updates the moment the
  setting changes, and shows both facts ("Android setting: On/Off, Service: running/not running"). A button always
  opens Accessibility settings scrolled to EarHop clipboard send. Note: updating the APK turned the service off on the
  phone, so turn it on again after an update.
* Automatic sending is Root, Helper or Accessibility, one at a time (Root is always shown; on a device without root it
  says so and keeps the current method). Switching stops the other method
  (including the helper, through Shizuku), so a copy is never sent twice.
* New Liquid Glass style look: flat frosted glass cards with a light rim (no gradients) on a plain AMOLED black
  background (plain light grey in light mode); the tab bar and the header show the page under them, blurred live
  (real blur on Android 12 and later, a softer scaled-down image before), frosted header on scroll, glass tab bar with
  a sliding highlight, haptic feedback (can be turned off in Appearance).
* Home: Needs attention card with one-tap fixes, large toggles for Switching, Clipboard and Noise (long press to pause),
  your devices at a glance, clipboard summary, recent switches.
* Buds: battery for left, right and case; earbud names under Advanced, applied without a restart.
* Clipboard: per-method status and setup steps, recent clips (memory only, tap to copy again), clearer names.
* Devices: scan the Mac's QR code inside EarHop; Save restarts EarHop only when the name or secret changed.
* Settings: Keep EarHop running shows battery status for EarHop and Shizuku; About has version, self test and Copy
  diagnostics (no secret or addresses). Activity: log filters and Share log.
* Checked on the phone (dark and light). Not yet tried: the tablet, QR scanning, copy detection with real copies.

## 2026-10-29, optional accessibility clipboard trigger

* Added an opt-in Android accessibility service for automatic phone clipboard sending when the shell-user helper is
  unavailable. It checks copy confirmation event text only, then briefly opens EarHop's existing send screen. Android
  accessibility access must also be enabled by the user. Detection is heuristic and may miss copies; it has not yet
  been verified on the real phone.

## 2026-10-29, always on, automatic earbuds, Shizuku helper, new Android sections

* Morning report "EarHop was idle": the phone log shows the service ran all night, but a call at 10:06 to 10:09 tried
  three times to take the Buds while no device had them (in the case), which put switching into safe mode until Resume
  at 10:14. Safe mode now ends by itself after 2 minutes, or at once when a device has the earbuds. Two new simulator
  tests on each platform.
* Automatic earbuds on Mac and Android (Auto, AirPods, Buds): the pair connected to this device, else the pair the
  device holding the earbuds reports. For that the status carries the profile (protocol v3 on Wi-Fi, two spare signed
  flag bits on Bluetooth LE; v1 and v2 still accepted; new test vectors in Python, Swift and Kotlin). Android switches
  live without restarting the service. The tablet must get this APK: until then it refuses the new Wi-Fi packets
  (Bluetooth LE still works).
* Clipboard helper: EarHop starts it through Shizuku whenever it is missing (checked every minute), once Shizuku is
  installed, started and has allowed EarHop. The Clipboard section shows the helper's state, a Start helper button and
  the Shizuku steps. Not yet tested: Shizuku is not installed on the phone.
* Android sections: Home (summary and main switches for earbuds and clipboard), Buds, Clipboard (new), Devices,
  Settings (with Keep EarHop running: battery and auto-start buttons), Activity. EarHop starts whenever the app opens,
  and the Running chip opens Settings instead of stopping it. Version 1.1.0. Checked on the phone with screenshots.

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
