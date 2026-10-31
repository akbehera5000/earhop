import CoreAudio
import Foundation

/// Speaker fallback: with the earbuds connected but out of your ears, audio you play comes from the Mac's built-in
/// speakers; putting a bud back in moves the sound to the earbuds again without stopping playback.
///
/// Unlike Android, the Mac cannot reliably tell that audio stopped: Music and other players keep their output open after
/// a pause, so "playing" stays true and a later press of play is not seen as a start. So the Mac moves the output while
/// it is silent instead: once EarHop has paused because both buds came out, the output goes to the speakers, and the
/// next press of play is heard there. Audio started with the buds already out (nothing was paused) moves as on Android.
struct SpeakerPolicy {
    enum Action { case toSpeaker, toEarbuds }

    /// True while EarHop has sent the sound to the speakers.
    private(set) var onSpeaker = false
    private var wasPlaying = false
    private var outSince: Date?

    /// Records what the app did: a switch that failed leaves the sound where it was.
    mutating func routed(_ action: Action, ok: Bool) {
        onSpeaker = action == .toSpeaker ? ok : (!ok && onSpeaker)
    }

    /// earPaused: EarHop paused playback because the earbuds came out (the ear pause is waiting to resume).
    mutating func update(now: Date, enabled: Bool, owner: Bool, ear: EarReading, playing: Bool, call: Bool, earPaused: Bool = false) -> Action? {
        let out = ear.trusted && ear.ear == .none
        let inEar = ear.trusted && (ear.ear == .both || ear.ear == .one)
        if out { if outSince == nil { outSince = now } } else if ear.ear != .unknown { outSince = nil }
        let started = playing && !wasPlaying
        wasPlaying = playing
        if onSpeaker {
            // The earbuds left this Mac: the sound is on the speakers anyway, nothing to undo.
            if !owner { onSpeaker = false; return nil }
            return inEar || !enabled ? .toEarbuds : nil
        }
        guard enabled, owner, !call, out, let since = outSince else { return nil }
        let outFor = now.timeIntervalSince(since)
        // Paused by EarHop with both buds out: move while silent (1 s after the pause, so it has landed).
        if earPaused && outFor >= 1 { return .toSpeaker }
        // A real start with the buds already out, at least 1.5 s after they came out so the pause is never mistaken for it.
        return started && outFor >= 1.5 ? .toSpeaker : nil
    }
}

/// Switches the Mac's sound output between the built-in speakers and the earbuds, which stay connected throughout.
enum OutputRoute {
    private static func prop(_ sel: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: sel, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    private static func devices() -> [AudioDeviceID] {
        var a = prop(kAudioHardwarePropertyDevices)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioDeviceID](repeating: 0, count: Int(size) / MemoryLayout<AudioDeviceID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size, &ids) == noErr else { return [] }
        return ids
    }

    private static func transport(_ id: AudioDeviceID) -> UInt32 {
        var a = prop(kAudioDevicePropertyTransportType)
        var t: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        return AudioObjectGetPropertyData(id, &a, 0, nil, &size, &t) == noErr ? t : 0
    }

    private static func hasOutput(_ id: AudioDeviceID) -> Bool {
        var a = prop(kAudioDevicePropertyStreams, kAudioObjectPropertyScopeOutput)
        var size: UInt32 = 0
        return AudioObjectGetPropertyDataSize(id, &a, 0, nil, &size) == noErr && size > 0
    }

    private static func uid(_ id: AudioDeviceID) -> String {
        var a = prop(kAudioDevicePropertyDeviceUID)
        var u: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &a, 0, nil, &size, &u) == noErr, let s = u?.takeRetainedValue() else { return "" }
        return s as String
    }

    static var defaultOutput: AudioDeviceID {
        var a = prop(kAudioHardwarePropertyDefaultOutputDevice)
        var id = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size, &id)
        return id
    }

    /// The Mac's own speakers.
    static var speakers: AudioDeviceID? {
        devices().first { transport($0) == kAudioDeviceTransportTypeBuiltIn && hasOutput($0) }
    }

    /// The earbuds' output device: Bluetooth, with this address in its ID.
    static func earbuds(_ address: String) -> AudioDeviceID? {
        let want = address.lowercased().filter { $0.isHexDigit }
        guard want.count == 12 else { return nil }
        return devices().first {
            let t = transport($0)
            return (t == kAudioDeviceTransportTypeBluetooth || t == kAudioDeviceTransportTypeBluetoothLE) && hasOutput($0)
                && uid($0).lowercased().filter { $0.isHexDigit }.contains(want)
        }
    }

    @discardableResult
    private static func setDefault(_ id: AudioDeviceID) -> Bool {
        var a = prop(kAudioHardwarePropertyDefaultOutputDevice)
        var dev = id
        let r = AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, UInt32(MemoryLayout<AudioDeviceID>.size), &dev)
        return r == noErr && defaultOutput == id
    }

    static func toSpeakers() -> Bool {
        guard let s = speakers else { Log.write("speaker: no built-in speakers found"); return false }
        let ok = setDefault(s)
        Log.write(ok ? "speaker: sound on the Mac's speakers" : "speaker: could not switch the sound output to the speakers")
        return ok
    }

    static func toEarbuds(_ address: String) -> Bool {
        guard let e = earbuds(address) else { Log.write("speaker: the earbuds' audio device is gone, nothing to switch back to"); return false }
        let ok = setDefault(e)
        Log.write(ok ? "speaker: sound back on the earbuds" : "speaker: could not switch the sound output back to the earbuds")
        return ok
    }
}
