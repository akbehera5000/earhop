import CryptoKit
import Foundation
import Network

/// Files and photos over Wi-Fi, on the clipboard port, any size, streamed straight to and from disk.
/// Stream: "EHF1" | header length (4, big endian) | header (a ClipCodec frame, kind "file", data = JSON {"name","size"})
/// | records | a record of length 0 at the end. Record: length (4) | AES-256-GCM combined (nonce, ciphertext, tag) of
/// up to 1 MB of the file, with "EHF1" + file id + record number (4) as associated data, so records cannot be reordered,
/// cut or mixed between files. Same format as FileStream in FileTransfer.kt.
enum FileStream {
    static let magic = Data("EHF1".utf8)
    static let chunk = 1 << 20

    static func aad(_ id: String, _ index: UInt32) -> Data {
        magic + Data(id.utf8) + Data([UInt8(index >> 24), UInt8((index >> 16) & 0xFF), UInt8((index >> 8) & 0xFF), UInt8(index & 0xFF)])
    }

    static func seal(_ data: Data, secret: String, id: String, index: UInt32) -> Data? {
        try? AES.GCM.seal(data, using: ClipCodec.key(secret), authenticating: aad(id, index)).combined
    }

    static func open(_ combined: Data, secret: String, id: String, index: UInt32) -> Data? {
        guard let box = try? AES.GCM.SealedBox(combined: combined) else { return nil }
        return try? AES.GCM.open(box, using: ClipCodec.key(secret), authenticating: aad(id, index))
    }

    static func be32(_ n: Int) -> Data { Data([UInt8((n >> 24) & 0xFF), UInt8((n >> 16) & 0xFF), UInt8((n >> 8) & 0xFF), UInt8(n & 0xFF)]) }
    static func int(_ d: Data) -> Int { d.reduce(0) { $0 << 8 | Int($1) } }

    /// A file name safe to save: no folders, no hidden names, at most 120 characters.
    static func safeName(_ n: String) -> String {
        var s = (n as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "-")
        while s.hasPrefix(".") { s.removeFirst() }
        if s.isEmpty { s = "file" }
        return String(s.prefix(120))
    }

    /// A free path in `dir` for `name` ("photo.jpg", then "photo 2.jpg", ...).
    static func freePath(_ dir: URL, _ name: String) -> URL {
        let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
        var url = dir.appendingPathComponent(name)
        var n = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        return url
    }
}

extension ClipTcp {
    /// Sends one file as a stream; `done` reports whether all of it was handed to the network.
    func sendFile(_ url: URL, header: Data, id: String, secret: String, to host: String, done: @escaping (Bool) -> Void) {
        guard let fh = try? FileHandle(forReadingFrom: url) else { done(false); return }
        let c = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        let q = DispatchQueue(label: "earhop.file.send")
        var finished = false
        func finish(_ ok: Bool) { if finished { return }; finished = true; try? fh.close(); c.cancel(); done(ok) }
        c.stateUpdateHandler = { st in if case .failed = st { finish(false) } }
        c.start(queue: q)
        var index: UInt32 = 0
        func next() {
            let data = (try? fh.read(upToCount: FileStream.chunk)) ?? nil
            guard let data, !data.isEmpty else {
                c.send(content: FileStream.be32(0), completion: .contentProcessed { err in finish(err == nil) })
                return
            }
            guard let sealed = FileStream.seal(data, secret: secret, id: id, index: index) else { finish(false); return }
            index += 1
            c.send(content: FileStream.be32(sealed.count) + sealed, completion: .contentProcessed { err in
                if err != nil { finish(false) } else { next() }
            })
        }
        c.send(content: FileStream.magic + FileStream.be32(header.count) + header, completion: .contentProcessed { err in
            if err != nil { finish(false) } else { next() }
        })
    }

    /// Receives a file stream after its "EHF1" was read. `accept` checks the header and returns where to write (nil
    /// refuses it); `done` gets the item and the written file, or nil when the stream broke off.
    func serveFile(_ c: NWConnection, secret: String, accept: @escaping (ClipItem) -> URL?, done: @escaping (ClipItem, URL?) -> Void) {
        final class Progress { var at = Date() }
        let progress = Progress()
        // Nothing for 30 s ends the transfer; a large file may take as long as it needs while data flows.
        func watch() {
            DispatchQueue.global().asyncAfter(deadline: .now() + 10) {
                if c.state == .cancelled { return }
                if Date().timeIntervalSince(progress.at) > 30 { c.cancel() } else { watch() }
            }
        }
        watch()
        func exactly(_ n: Int, _ then: @escaping (Data?) -> Void) {
            c.receive(minimumIncompleteLength: n, maximumLength: n) { d, _, _, err in
                progress.at = Date()
                then(err == nil && d?.count == n ? d : nil)
            }
        }
        exactly(4) { lenData in
            guard let lenData else { c.cancel(); return }
            let hl = FileStream.int(lenData)
            guard hl > 0, hl < 65536 else { c.cancel(); return }
            exactly(hl) { h in
                guard let h, let item = ClipCodec.decode(h, secret: secret), item.kind == "file", let dest = accept(item),
                      FileManager.default.createFile(atPath: dest.path, contents: nil), let out = try? FileHandle(forWritingTo: dest) else {
                    c.cancel(); return
                }
                var index: UInt32 = 0
                func fail() { try? out.close(); try? FileManager.default.removeItem(at: dest); c.cancel(); done(item, nil) }
                func record() {
                    exactly(4) { l in
                        guard let l else { fail(); return }
                        let n = FileStream.int(l)
                        if n == 0 { try? out.close(); c.cancel(); done(item, dest); return }
                        guard n <= FileStream.chunk + 64 else { fail(); return }
                        exactly(n) { r in
                            guard let r, let plain = FileStream.open(r, secret: secret, id: item.id, index: index) else { fail(); return }
                            index += 1
                            out.write(plain)
                            record()
                        }
                    }
                }
                record()
            }
        }
    }
}
