import Foundation
import CoreAudio
import AppKit
import ApplicationServices

enum AudioMonitor {
    /// True when the default output device has a running stream anywhere.
    static func isPlaying() -> Bool {
        var dev = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultOutputDevice,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &dev) == noErr else { return false }
        var running: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        addr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere,
                                          mScope: kAudioObjectPropertyScopeGlobal,
                                          mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(dev, &addr, 0, nil, &size, &running) == noErr else { return false }
        return running != 0
    }

    /// True when the default input device (a microphone) is in use, a sign of a call or dictation.
    static func isMicInUse() -> Bool {
        var dev = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &dev) == noErr else { return false }
        var running: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        addr = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere,
                                          mScope: kAudioObjectPropertyScopeGlobal,
                                          mElement: kAudioObjectPropertyElementMain)
        guard AudioObjectGetPropertyData(dev, &addr, 0, nil, &size, &running) == noErr else { return false }
        return running != 0
    }

    /// Bundle ids of processes currently producing audio output, or nil if the API is unavailable.
    static func outputApps() -> [String]? {
        guard #available(macOS 14.2, *) else { return nil }
        var addr = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyProcessObjectList,
                                              mScope: kAudioObjectPropertyScopeGlobal,
                                              mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size) == noErr, size > 0 else { return nil }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &addr, 0, nil, &size, &ids) == noErr else { return nil }
        var apps: [String] = []
        for id in ids {
            var running: UInt32 = 0
            var s = UInt32(MemoryLayout<UInt32>.size)
            var a = AudioObjectPropertyAddress(mSelector: kAudioProcessPropertyIsRunningOutput,
                                               mScope: kAudioObjectPropertyScopeGlobal,
                                               mElement: kAudioObjectPropertyElementMain)
            guard AudioObjectGetPropertyData(id, &a, 0, nil, &s, &running) == noErr, running != 0 else { continue }
            var cf: Unmanaged<CFString>?
            var bs = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
            a.mSelector = kAudioProcessPropertyBundleID
            if AudioObjectGetPropertyData(id, &a, 0, nil, &bs, &cf) == noErr, let b = cf?.takeRetainedValue() as String?, !b.isEmpty {
                apps.append(b)
            } else {
                apps.append("unknown")
            }
        }
        return apps
    }

    /// Posting a media key needs the Accessibility permission. Rebuilding the app resets it, so check and ask.
    static var canPostKeys: Bool { AXIsProcessTrusted() || CGPreflightPostEventAccess() }

    static func requestKeyPermission() {
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(opts)
        _ = CGRequestPostEventAccess()
    }

    /// Opens the pane where Accessibility (Device Control and Data Access on newer macOS) is allowed.
    static func openKeyPermissionSettings() {
        let urls = ["x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension?Privacy_Accessibility",
                    "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility",
                    "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension",
                    "x-apple.systempreferences:"]
        for u in urls {
            if let url = URL(string: u), NSWorkspace.shared.open(url) { return }
        }
        NSWorkspace.shared.open(URL(fileURLWithPath: "/System/Applications/System Settings.app"))
    }

    /// Sends the system Play/Pause media key.
    static func sendPlayPause() {
        if !canPostKeys {
            Log.write("media key blocked: allow EarHop in System Settings > Privacy & Security > Accessibility, then try again")
            requestKeyPermission()
        }
        let keyPlay = 16
        for down in [true, false] {
            let state = down ? 0xA : 0xB
            let data1 = (keyPlay << 16) | (state << 8)
            let ev = NSEvent.otherEvent(with: .systemDefined, location: .zero,
                                        modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(state << 8)),
                                        timestamp: 0, windowNumber: 0, context: nil,
                                        subtype: 8, data1: data1, data2: -1)
            ev?.cgEvent?.post(tap: .cghidEventTap)
        }
    }
}

/// Pause and play for the Mac. The Play/Pause key is a toggle: pressing it when the audio is already paused starts it
/// again, on the speakers once the earbuds are gone. So EarHop only sends explicit Pause and Play commands through the
/// system media service; they cannot restart paused audio.
///
/// EarHop never presses the toggle key on its own. It used to press it when audio still seemed to play 6 s after a
/// pause, but browsers keep their audio output open long after pausing, so that check was wrong and the key restarted
/// the music (logs of 2026-10-28, "apps: helper"). The key is used only when the user picks it in Settings, or when the
/// system media service is missing.
enum MediaControl {
    private typealias SendFn = @convention(c) (Int32, CFDictionary?) -> Bool
    private static let send: SendFn? = {
        guard let h = dlopen("/System/Library/PrivateFrameworks/MediaRemote.framework/MediaRemote", RTLD_NOW),
              let p = dlsym(h, "MRMediaRemoteSendCommand") else { return nil }
        return unsafeBitCast(p, to: SendFn.self)
    }()
    private static let play: Int32 = 0, pause: Int32 = 1

    static var useKeyOnly = false
    /// Set by the app: whether this Mac is producing sound right now (only used for the log).
    static var isPlaying: () -> Bool = { AudioMonitor.isPlaying() }
    private static var generation = 0

    static var mode: String { (useKeyOnly || send == nil) ? "Play/Pause key" : "Pause and Play commands" }

    /// Short names of the apps producing sound, for the log.
    static func appNames() -> String {
        let a = AudioMonitor.outputApps() ?? []
        return a.isEmpty ? "none" : a.map { $0.split(separator: ".").last.map(String.init) ?? $0 }.joined(separator: ",")
    }

    static func pausePlayback() { command(pause, wantPlaying: false) }
    static func resumePlayback() { command(play, wantPlaying: true) }

    private static func command(_ cmd: Int32, wantPlaying: Bool) {
        generation += 1
        guard !useKeyOnly, let send = send else {
            Log.write("media: Play/Pause key (\(wantPlaying ? "play" : "pause"))")
            AudioMonitor.sendPlayPause()
            return
        }
        let gen = generation
        let ok = send(cmd, nil)
        Log.write("media: explicit \(wantPlaying ? "play" : "pause") sent (accepted=\(ok), apps: \(appNames()))")
        // Observe only, never act: a browser's output can stay open for many seconds after a real pause.
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) {
            guard gen == generation, isPlaying() != wantPlaying else { return }
            Log.write("media: output still \(wantPlaying ? "silent" : "open") 6 s after explicit \(wantPlaying ? "play" : "pause") (apps: \(appNames())); no key is sent")
        }
    }
}

/// Decides when microphone use is a real call. Dictation, voice tools and a meeting app with an idle open microphone
/// use the microphone without sound coming out, so a call needs the microphone and the speaker busy together for 2 s.
/// It ends once the microphone has been free for 2 s (a call can be silent for a moment, so output alone does not end it).
struct CallDetector {
    private var bothSince: Date?
    private var micFreeSince: Date?
    private(set) var inCall = false

    mutating func update(now: Date, mic: Bool, output: Bool) -> Bool {
        if mic && output { if bothSince == nil { bothSince = now } } else { bothSince = nil }
        if mic { micFreeSince = nil } else if micFreeSince == nil { micFreeSince = now }
        if !inCall, let s = bothSince, now.timeIntervalSince(s) >= 2 { inCall = true }
        if inCall, let f = micFreeSince, now.timeIntervalSince(f) >= 2 { inCall = false }
        return inCall
    }
}
