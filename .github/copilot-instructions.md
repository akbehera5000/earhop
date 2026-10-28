# Instructions for GitHub Copilot

Read `AGENTS.md` in the repository root before making changes. It describes the Mac app (Swift, `mac/`), the Android
app (Kotlin, `android/`), the shared protocol, the switching rules and the mistakes that must not be repeated.

The most important rules:

* All switching decisions live in `HandoffCore.swift` and `HandoffCore.kt`, which must stay identical. Change both,
  and add a simulator test on both (`SimTests.swift`, `HandoffSimTest.kt`) that fails without the change.
* Keep the protocol identical in Swift, Kotlin and `tools/earhop_tool.py`, with the test vectors in `AGENTS.md`.
* Never send a second Play/Pause after EarHop paused or released. Never treat audio right after a pause or release as
  a new start.
* Test modes must never write the user's config or log (`Config.readOnly`, `Log.toFile`).
* Run `bash tests/run.sh` after every change. Say clearly what was only tested automatically and what was tried on
  real devices.
* No em dashes in code, comments or documents. Reliability over speed. Ask before big changes.
