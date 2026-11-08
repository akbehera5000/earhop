import CoreAudio
import Foundation

/// A test build of 360 Audio (removed again) moved the Mac's sound output to the BlackHole virtual device and saved the
/// previous output under `spatialRestoreOutputUID`. If that was never undone, put the output back once at launch: on
/// the saved device when it is there, else on the built-in output. Without the saved key nothing is touched, so a
/// BlackHole output the user chose stays.
enum OutputRestore {
    static let key = "spatialRestoreOutputUID"

    static func run() {
        guard let uid = UserDefaults.standard.string(forKey: key) else { return }
        guard let out = defaultOutput(), name(out).localizedCaseInsensitiveContains("BlackHole") else {
            UserDefaults.standard.removeObject(forKey: key)
            return
        }
        guard let back = devices().first(where: { string($0, kAudioDevicePropertyDeviceUID) == uid }) ?? builtInOutput() else { return }
        var a = prop(kAudioHardwarePropertyDefaultOutputDevice)
        var d = back
        if AudioObjectSetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, UInt32(MemoryLayout<AudioObjectID>.size), &d) == noErr {
            UserDefaults.standard.removeObject(forKey: key)
            Log.write("audio: put the sound output back on \(name(back)) (it was left on BlackHole)")
        }
    }

    private static func prop(_ s: AudioObjectPropertySelector, _ scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: s, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    private static func devices() -> [AudioObjectID] {
        var a = prop(kAudioHardwarePropertyDevices)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size, &ids) == noErr else { return [] }
        return ids
    }

    private static func string(_ dev: AudioObjectID, _ sel: AudioObjectPropertySelector) -> String? {
        var a = prop(sel)
        var v: Unmanaged<CFString>?
        var s = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(dev, &a, 0, nil, &s, &v) == noErr, let r = v?.takeRetainedValue() else { return nil }
        return r as String
    }

    private static func name(_ dev: AudioObjectID) -> String { string(dev, kAudioObjectPropertyName) ?? "?" }

    private static func defaultOutput() -> AudioObjectID? {
        var a = prop(kAudioHardwarePropertyDefaultOutputDevice)
        var dev = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &a, 0, nil, &size, &dev) == noErr, dev != kAudioObjectUnknown else { return nil }
        return dev
    }

    /// The Mac's own speakers or headphone jack (a built-in device with output streams).
    private static func builtInOutput() -> AudioObjectID? {
        devices().first { dev in
            var t = prop(kAudioDevicePropertyTransportType)
            var transport: UInt32 = 0
            var s = UInt32(MemoryLayout<UInt32>.size)
            guard AudioObjectGetPropertyData(dev, &t, 0, nil, &s, &transport) == noErr, transport == kAudioDeviceTransportTypeBuiltIn else { return false }
            var st = prop(kAudioDevicePropertyStreams, kAudioObjectPropertyScopeOutput)
            var size: UInt32 = 0
            return AudioObjectGetPropertyDataSize(dev, &st, 0, nil, &size) == noErr && size > 0
        }
    }
}
