# Writing an EarHop app for another platform (Windows, Linux, ...)

EarHop 1.3 and later accept any number of devices with free names, so a new platform only has to speak the same
protocol. This page lists everything a new app needs. The Mac app (`mac/Sources/EarHop`) and the Android app
(`android/app/src/main/kotlin/com/earhop/logger`) are the reference implementations; `tools/earhop_tool.py` implements
the status protocol in Python with the test vectors, and AGENTS.md section 6 has the exact formats.

## 1. Identity

* **Name:** 1 to 20 characters, lower case letters, digits and `-`, starting with a letter or digit, different on
  every device (for example `work-pc`).
* **Shared secret:** the same string on every device (the Mac shows it as a QR code in Settings, Network).
* **Device id** (Bluetooth LE only): the first two bytes, big endian, of `HMAC-SHA256(secret, "earhop dev v4|" + name)`.

## 2. Status (what a device is doing), sent about once a second and at once on a change

* **Wi-Fi:** one UDP datagram to port 47474, broadcast on every network the device is on, plus unicast to addresses
  learned from signed packets. JSON v4:
  `{"v":4,"dev":name,"ts":<your clock, ms>,"seq":n,"playing":0|1,"ear":"both|one|none|unknown","owner":0|1,"call":0|1,"prof":"airpods|buds|","att":0|1,"sig":hex}`,
  `sig = HMAC-SHA256(secret, "4|dev|ts|seq|playing|ear|owner|call|prof|att")`.
* **Bluetooth LE:** a 16 byte payload advertised as one 128 bit service UUID, connectable:
  `0xEB | id (2) | seq (2) | flags | first 6 bytes of HMAC(bytes 0..5 + 12..15) | seconds since 1970 (4)`.
* One sequence number for both links. Owner means the earbuds are connected to this device.

## 3. Receiving status

1. Check the signature (and the name for v4).
2. **ClockGuard:** learn each device's clock offset from its first copy; accept copies within 15 s (Wi-Fi) or 90 s
   (Bluetooth LE) of it; relearn after 3 copies in a row with rising sequence numbers that agree on a new offset.
3. **PeerOrder:** take a copy only if its sequence number is newer; a number more than 300 lower counts as a restart only
   if its time stamp is also newer.

## 4. Switching rules

Implement `HandoffCore` (HandoffCore.swift and HandoffCore.kt are the same pure state machine with tests): newest
playback wins, calls first, pause on ear removal, release after 2 minutes out of the ears, safe mode after 3 failed
claims. Port the simulator tests with it. Platform work: detect playing audio, calls, connect and disconnect the
earbuds, and send Pause and Play.

## 5. Clipboard, files, links and messages

* **Frame:** `"EHC1" | nonce (12) | AES-256-GCM(key, header length (2) | header JSON | data) | tag`, associated data
  `"EHC1"`, key `HMAC-SHA256(secret, "earhop clip v1")`. Header `{dev,id,kind,mime,[to],ts,v:1}`.
* **Kinds:** `text`, `image`, `link` (content), `ack` (receipt, data = id), `diag-req`, `diag`, `batt`
  (`L,R,case,chargingL,chargingR,chargingCase`), `vol` (0 to 1), `settings` (`{"v":{...},"t":{...}}`),
  `hello` (`{"type":"windows","app":"windows","version":"1.4.0"}`: what the device is, for its icon on the other
  devices; send it at start, when a device joins and every 10 minutes).
* **Wi-Fi:** TCP port 47475, one `length (4) | frame` per connection. Accept connections only from addresses learned
  from signed status packets.
* **Bluetooth LE:** GATT service `EAC1B000-5EED-4C1E-8A00-000000000001`, inbox `...B001` (write, encrypted), name
  `...B002` (read, encrypted); chunks `tag (4) | index (2) | count (2) | payload (172)`.
* **Files:** on port 47475, `"EHF1" | header length (4) | header frame (kind file, data {"name","size"}) | records | 0`,
  each record `length (4) | AES-GCM combined` of up to 1 MB with associated data `"EHF1" + id + record number (4)`.
* **Receipts:** answer every applied `text`, `image`, `link` and `file` with an `ack` to its sender, also for repeats.

## 6. What to test against the reference apps

Run `python3 tools/earhop_tool.py selftest` and check the v4 vectors in AGENTS.md section 6. Then join a Mac or an
Android device: it should appear on their Status page with the right link and clock, and Copy diagnostics there should
include a reply from the new device.
