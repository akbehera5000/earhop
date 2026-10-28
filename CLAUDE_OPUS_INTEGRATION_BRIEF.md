# EarHop: Claude Opus integration brief

## Mission

Take over the EarHop codebase and turn it into a reliable local ecosystem app for:

- Mac running macOS
- Android phone
- Rooted Android tablet
- AirPods Pro 2
- Samsung Galaxy Buds2 Pro

The desired result is one polished app ecosystem. When media starts playing on a device, the earbuds should move to that device. The previous owner must pause and release cleanly. Removing an earbud must pause playback, and putting it back must resume only when EarHop caused or clearly observed the pause. Calls must have priority. Wi-Fi UDP is primary, Bluetooth LE is fallback, and there must be no cloud service or account requirement.

Do not treat this as a greenfield rewrite. Preserve the existing working protocol, tests, UI functionality, and safety behavior. Make surgical changes, run validation after every coherent change, and test on real hardware before claiming success.

## Mandatory first steps

1. Read `HANDOFF.md`, `README.md`, and this file.
2. Inspect every current implementation before changing it:
   - Mac: `Controller.swift`, `AudioMonitor.swift`, `BudsWear.swift`, `BudsProtocol.swift`, `EarMonitor.swift`, `Protocol.swift`, `Link.swift`, `Discovery.swift`, `BleLink.swift`, `main.swift`, `PopoverView.swift`, `SettingsView.swift`, `Glass.swift`.
   - Android: `HandoffController.kt`, `EarPolicy.kt`, `EarScanner.kt`, `AacpClient.kt`, `BudsClient.kt`, `BudsProtocol.kt`, `Claimer.kt`, `EarService.kt`, `Rules.kt`, `MainActivity.kt`, `Proto.kt`, `UdpLink.kt`, `BleLink.kt`, `EhLog.kt`.
3. Establish a clean baseline:
   - `python3 tools/earhop_tool.py selftest`
   - `bash tests/run.sh`
   - `cd android && ./gradlew testDebugUnitTest`
   - Build the Mac release target.
4. Create a test matrix for both earbud families on all three devices.
5. Do not copy external code until its license and the intended distribution model have been reviewed.

## Current EarHop architecture to preserve

### Shared handoff model

The current protocol is signed HMAC-SHA256 JSON over UDP port `47474`. Device names are exactly `mac`, `phone`, and `tab`. Status includes:

```text
dev, ts, seq, playing, ear, owner, call
```

The handoff rule is newest playback wins, with priority only breaking a near-simultaneous tie. The current owner must release when another device has genuinely started later. Call priority overrides normal playback. Claim failures enter safe mode. A device that loses or releases the earbuds must not immediately reclaim while its audio is stale.

Do not weaken:

- HMAC verification
- timestamp and replay protection
- safe mode
- manual Take and Release
- automatic claim gates
- claim blocking after a loss or release
- dry-run mode
- call priority
- per-device volume profiles

### Required state-machine properties

Implement and test explicit states rather than scattered booleans:

```text
Idle
PlayingWithoutEarbuds
ClaimPending
OwnerPlaying
OwnerPausedByEarRemoval
ReleasePending
LostOwnership
CallPriority
SafeMode
```

Every transition must log:

```text
timestamp, device, earbud profile, local playing, local owner,
ear state and source, peer owner, peer playingSince, action, reason
```

Never use a media toggle as a substitute for a known pause or play action unless the platform has no other option. A second pause key after EarHop has already paused or released is a critical bug because it can restart audio on speakers.

## External repositories and how to use them

These are research references. Do not blindly merge them, vendor whole repositories, or copy code without checking the license and attribution requirements.

### AirPods Pro 2

#### LibrePods

https://github.com/librepods-org/librepods  
License: GPL-3.0.

Use it to study:

- AirPods proprietary control channel
- direct ear detection
- battery and case status
- noise-control configuration
- head gestures and conversational awareness
- Android behavior when the regular beacon is delayed

Priority task: determine whether the Android implementation can provide a reliable second ear-state source on the phone without root. Keep it behind an interface and feature flag. Do not let experimental direct-channel failures block beacon scanning or normal handoff.

Do not copy GPL implementation files into a closed or differently licensed EarHop distribution without making a deliberate licensing decision.

#### CAPod

https://github.com/d4rken-org/capod  
Repository contains GPL-licensed code. Verify the exact license files before reuse.

Use it to compare:

- Apple manufacturer-data parsing
- AirPods model identification
- battery and case interpretation
- nearby device filtering
- automatic play/pause debounce
- handling of stale and partial ear-state reports

The specific EarHop problem to investigate is that the phone sees the primary bud removal immediately but the secondary bud can be delayed by 24 to 33 seconds. Add source and confidence tracking rather than replacing a reliable `none` state with a delayed `one` state.

#### OpenPods

https://github.com/adolfintel/OpenPods  
License: GPL-3.0.

Use as a secondary Android reference for:

- battery and case status
- nearby AirPods discovery
- Android Bluetooth behavior
- media pause and resume behavior

#### AirPodsDesktop

https://github.com/SpriteOvO/AirPodsDesktop  
License: GPL-3.0.

Use for cross-platform ideas around:

- ear detection
- playback control
- device status presentation

Do not make its Windows transport assumptions part of the Mac or Android core.

#### AirPods Pro Motion Sampler

https://github.com/tukuyo/AirPodsPro-Motion-Sampler  
License: MIT.

Optional future reference for motion sensors and head gestures. It is not a handoff fix and should not delay reliability work.

#### Omarchy Pods

https://github.com/thisisgm/omarchy-pods  
License: MIT.

Use for feature mapping and device abstraction ideas around:

- battery
- listening modes
- adaptive noise
- conversation awareness
- one-bud ANC
- ear detection

### Galaxy Buds2 Pro

#### GalaxyBudsClient

https://github.com/timschneeb/GalaxyBudsClient  
Repository is GPL-3.0. Confirm the repository license before using any code.

This is the primary protocol reference. Study:

- `GalaxyBudsRFCommProtocol.md`
- SPP and RFCOMM connection lifecycle
- Buds2 Pro model support
- status message IDs and payload offsets
- CRC and frame validation
- battery and placement reporting
- ANC, ambient, equalizer, touch lock, quick connect, spatial audio
- reconnect and disconnect behavior

Compare all findings with:

- `mac/Sources/EarHop/BudsProtocol.swift`
- `mac/Sources/EarHop/BudsWear.swift`
- `android/.../BudsProtocol.kt`
- `android/.../BudsClient.kt`

Do not rewrite the existing parser without tests for every frame type. Add golden frame fixtures and malformed-frame tests first.

#### LiveBudsCli

https://github.com/JojiiOfficial/LiveBudsCli  
License: GPL-3.0.

Use as an independent implementation to validate:

- Buds2 Pro support
- ANC and ambient commands
- equalizer and touch lock
- battery and advanced status
- connect and disconnect behavior

When two implementations disagree, capture real RFCOMM frames from the Buds2 Pro and prefer verified hardware behavior over assumptions.

#### MagicPodsCore

https://github.com/steam3d/MagicPodsCore  
License: GPL-3.0.

Use for:

- cross-brand device abstraction
- AirPods and Galaxy Buds2 Pro model tables
- battery and noise-control behavior
- capability detection

Do not import its UI or platform-specific assumptions. Extract only independently verified protocol facts.

#### Budsy

https://github.com/vedatkilic/budsy  
License: MIT.

This is the best direct Mac reference because it is a native macOS Galaxy Buds application. Study and, where compatible, reuse small isolated MIT-licensed components for:

- RFCOMM lifecycle
- Buds2 Pro battery
- ANC and ambient mode
- equalizer
- touch controls
- fit status
- reconnect handling

Preserve EarHop’s ownership and handoff controller. Budsy must not become a second competing connection manager.

#### Galaxy Continuity for macOS

https://github.com/Clascheurduturfu/galaxy-continuity-mac  
License: MIT.

Use for architecture ideas only:

- Bluetooth LE and Classic Bluetooth transport separation
- reconnect backoff
- wake and network transition handling
- nearby device discovery
- pause and resume controls

Do not add Samsung account login, cloud services, or unrelated continuity features to EarHop. EarHop must remain local, private, and account-free.

## Recommended implementation plan

### Phase 1: baseline and observability

Before adding features:

1. Add structured event logging on Mac and Android.
2. Add a visible state-machine status card.
3. Log ear-state source and confidence:

```text
source=buds-rfcomm
source=airpods-beacon
source=airpods-direct
source=last-known
confidence=high|medium|low
```

4. Add protocol fixture tests for:
   - AirPods Pro 2 beacon samples
   - Galaxy Buds2 Pro status frames
   - malformed lengths
   - bad CRC
   - bad HMAC
   - stale timestamps
   - replayed sequence numbers
5. Add a deterministic handoff simulator that can replay a timeline of local and peer events without Bluetooth hardware.

### Phase 2: AirPods Pro 2 provider improvements

Create a common provider interface:

```text
EarbudStateProvider
  currentState
  source
  confidence
  battery
  capabilities
  start()
  stop()
```

Implement:

1. Beacon provider, retaining the current working parser.
2. Optional direct AirPods provider informed by LibrePods.
3. A fusion layer that:
   - treats `none` as high-confidence when both-out evidence is present
   - does not wait 30 seconds for a delayed secondary-bud event
   - does not oscillate between `one` and `both`
   - expires stale direct-channel data
   - exposes confidence to the handoff controller
4. Use confidence-aware rules:
   - high-confidence `none` can pause immediately
   - low-confidence `one` may arm a pause but require confirmation when configured
   - resume only from a valid transition and within the configured resume window

### Phase 3: Galaxy Buds2 Pro provider improvements

Implement one shared conceptual protocol model in Swift and Kotlin:

```text
BudsConnectionState
BudsWearState
BudsCapabilities
BudsCommand
BudsStatus
```

Required behavior:

- bounded connection timeout
- exponential reconnect backoff
- no busy loop
- clean close before A2DP release
- stale status timeout
- explicit command acknowledgement or timeout
- serialized commands
- no command race with status polling
- recovery when Galaxy Wearable or Quick Connect competes for RFCOMM
- preserve last known wear state for a short, clearly logged grace period

The Mac and Android implementations must agree on:

- placement nibble meanings
- `0x60` and `0x61` status offsets
- CRC16 XModem
- ANC, ambient, equalizer, spatial audio, touch lock, quick connect
- safe Find My Buds start and stop behavior

### Phase 4: handoff reliability

Keep the newest-playback-wins policy, but formalize it and test these timelines:

1. Mac owns, phone starts playing.
2. Phone owns, Mac starts playing.
3. Owner pauses, new device starts within two seconds.
4. Old owner remains `playing=true` because an app holds audio focus.
5. Owner releases but Bluetooth disconnect takes several seconds.
6. New claim starts while the old owner is still connected.
7. Peer disappears during a claim.
8. Wi-Fi disappears and BLE fallback takes over.
9. A call begins on the phone while another device owns the Buds.
10. Manual Take occurs while auto switching is gated.
11. Earbud removal occurs while ownership changes.
12. Quick Connect moves the Buds without EarHop initiating it.

Required safeguards:

- no second pause after EarHop already paused or released
- no automatic reclaim while local audio is stale
- no claim while a fresh owner peer is still connected unless the handoff timeout expires
- a failed claim must not create a rapid claim loop
- manual Take always remains available
- every failed transition has an actionable log reason

### Phase 5: transport resilience

Verify and improve:

- UDP broadcast
- Bonjour or mDNS discovery
- direct unicast to discovered peers
- BLE fallback after the existing timeout
- Wi-Fi return after the existing stabilization period
- Android background and battery restrictions
- Mac sleep and wake
- router AP isolation
- network changes

Do not silently fall back to an insecure or unsigned packet.

## UI direction: Apple-inspired Liquid Glass language

Create a coherent visual system on both platforms. It may be inspired by Apple’s Liquid Glass design language, but do not copy Apple assets or claim official affiliation.

### Visual principles

- AMOLED black or deep graphite base
- translucent layered cards with subtle blur
- bright edge highlights and soft inner shadows
- one accent color per active earbud profile
  - AirPods: cool white or blue
  - Galaxy Buds: violet or cyan
- large readable state typography
- restrained motion, never decorative motion during a handoff
- clear high-contrast fallback when blur is unavailable
- accessible labels and Dynamic Type or equivalent scaling
- respect Reduce Motion and high-contrast settings

### Main dashboard

Show one primary glass card:

```text
EarHop
AirPods Pro 2 / Galaxy Buds2 Pro
Connected to Mac / Phone / Tab
Playing / Paused / Ready / Switching
```

Include:

- animated earbud illustration or simple vector silhouette
- left, right, and case battery
- current wear state
- active device
- transport: Wi-Fi or Bluetooth LE
- confidence and source in an expandable diagnostic row
- one large manual Take button
- one Release button
- a compact activity timeline

### Switching presentation

When switching:

1. Freeze the main card’s layout.
2. Show a short progress ring with stages:
   - Pausing old device
   - Releasing earbuds
   - Connecting here
   - Confirming ownership
3. Show a cancel or manual override only if it is safe.
4. Never imply success until ownership has been verified.
5. If it fails, explain the exact next action:
   - “The Mac is still connected”
   - “Bluetooth connection was blocked”
   - “Audio is still active on the previous device”
   - “Connect from Bluetooth settings”

### Settings

Use grouped glass panels:

- Earbud profile
- Devices and priority
- Automatic switching
- Pause on ear removal
- Calls and priority
- Quiet hours and Wi-Fi gates
- Transport and pairing
- Buds controls
- Diagnostics and logs
- Permissions

Do not hide important safety controls behind decorative interactions.

### Mac-specific UI

- menu bar icon shows owner, battery, and a small connection state
- popover is compact and keyboard navigable
- settings window provides full diagnostics
- global shortcut remains available
- include a Copy Log button
- show Accessibility, Bluetooth, and Local Network permission status

### Android-specific UI

- foreground service status is always clear
- onboarding must explain Bluetooth, nearby devices, location, notification access, battery exemption, and root requirements
- quick settings tiles: Take, Release, Auto
- home widget shows active device and connection state
- settings remain usable on the phone and the tablet
- never rely on a notification alone for critical error information

## Licensing and attribution rules

1. Prefer MIT references for directly reusable code:
   - Budsy
   - Galaxy Continuity for macOS
   - Omarchy Pods
   - AirPods Pro Motion Sampler
2. Treat GPL projects as protocol and behavior references unless EarHop’s distribution and licensing are deliberately changed:
   - LibrePods
   - CAPod
   - OpenPods
   - AirPodsDesktop
   - GalaxyBudsClient
   - LiveBudsCli
   - MagicPodsCore
3. Never copy a repository’s code while omitting its license or attribution.
4. Do not upload EarHop source, logs, pairing secrets, Bluetooth addresses, or documents to external services.
5. Do not include private user data in issue reports or test fixtures.
6. Keep third-party notices in the repository if any external code is reused.

## Required tests and acceptance criteria

### Automated

- Python protocol self-test passes.
- Swift build and self-test pass.
- Android unit tests pass.
- New deterministic handoff simulator tests pass.
- New AirPods and Buds frame fixtures pass.
- No compiler warnings introduced in changed code unless documented.

### Real-device

For both AirPods Pro 2 and Galaxy Buds2 Pro:

- Mac to phone switch
- phone to Mac switch
- Mac to Tab switch
- Tab to Mac switch
- phone call while another device owns the earbuds
- pause on old device after transfer
- ear removal and reinsertion
- one-bud and both-bud transitions
- Bluetooth disconnect delay
- Quick Connect enabled and disabled
- Wi-Fi loss and BLE fallback
- app restart
- device sleep and wake

Record synchronized logs from all participating devices. Every scenario must identify:

- event start time
- device that started playback
- first peer observation time
- release request time
- Bluetooth disconnect confirmation
- claim request time
- ownership confirmation
- final playback state

Do not claim “reliable” until the handoff succeeds repeatedly across these scenarios without duplicate pause keys, speaker playback, stale ownership, or claim loops.

## Final deliverables

1. Working Mac app.
2. Working Android APK for the phone and the tablet.
3. Updated `HANDOFF.md` with verified behavior and remaining limitations.
4. Updated `README.md` with build, permission, pairing, and troubleshooting instructions.
5. Tests and fixtures for every protocol or state-machine change.
6. A short changelog describing which external projects informed each component.
7. No secrets, private logs, or personal device identifiers committed to the repository.

The priority order is:

1. Correct handoff and playback safety.
2. Reliable ear detection.
3. Reliable Buds2 Pro RFCOMM control.
4. Transport and background resilience.
5. Battery and advanced earbud controls.
6. Liquid Glass polish.

Never sacrifice the first four priorities for visual polish.
