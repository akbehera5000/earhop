import Foundation

/// UDP broadcast sender plus a blocking listener thread.
final class Link {
    private let port: UInt16
    private let secret: String
    private let selfName: String
    private var sendFd: Int32 = -1
    private var recvFd: Int32 = -1
    var onStatus: ((Status) -> Void)?
    /// A device whose packets are refused: (device, "secret" or "clock"). Called at most once a minute per device.
    var onRejected: ((String, String) -> Void)?
    private var rejectedAt: [String: Date] = [:]
    var extraTargets: () -> [Data] = { [] }
    private(set) var lastHeard = Date.distantPast
    /// Addresses learned from packets that passed the signature check, so peers are reached even when broadcasts are dropped.
    private var learned: [String: [Data]] = [:]
    /// Per device: the address and sequence number of its last packet, to spot two devices using one name.
    private var lastFrom: [String: (addr: Data, seq: Int, at: Date)] = [:]
    private var duplicateAt: [String: Date] = [:]
    /// Two devices send under the same name. Called at most once a minute per name.
    var onDuplicate: ((String) -> Void)?
    private let learnedLock = NSLock()

    init(port: Int, secret: String, selfName: String) {
        self.port = UInt16(port)
        self.secret = secret
        self.selfName = selfName
    }

    private func addr(_ ip: UInt32) -> sockaddr_in {
        var a = sockaddr_in()
        a.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        a.sin_family = sa_family_t(AF_INET)
        a.sin_port = port.bigEndian
        a.sin_addr.s_addr = ip.bigEndian
        return a
    }

    func start() {
        sendFd = socket(AF_INET, SOCK_DGRAM, 0)
        var on: Int32 = 1
        setsockopt(sendFd, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size))

        recvFd = socket(AF_INET, SOCK_DGRAM, 0)
        setsockopt(recvFd, SOL_SOCKET, SO_REUSEADDR, &on, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(recvFd, SOL_SOCKET, SO_REUSEPORT, &on, socklen_t(MemoryLayout<Int32>.size))
        var a = addr(INADDR_ANY)
        let rc = withUnsafePointer(to: &a) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(recvFd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        if rc != 0 { Log.write("link: bind failed errno=\(errno)") }

        let fd = recvFd
        let thread = Thread { [weak self] in
            var buf = [UInt8](repeating: 0, count: 2048)
            while true {
                var from = sockaddr_in()
                var len = socklen_t(MemoryLayout<sockaddr_in>.size)
                let n = withUnsafeMutablePointer(to: &from) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buf, buf.count, 0, $0, &len) }
                }
                if n <= 0 { if n < 0 && errno != EINTR { return }; continue }
                guard let self = self else { return }
                let data = Data(buf[0..<n])
                if let s = Proto.decode(data, secret: self.secret, nowMs: Proto.nowMs()), s.dev != self.selfName {
                    self.lastHeard = Date()
                    from.sin_port = self.port.bigEndian
                    let addr = withUnsafeBytes(of: &from) { Data($0) }
                    let now = Date()
                    self.learnedLock.lock()
                    var known = self.learned[s.dev] ?? []
                    let isNew = !known.contains(addr)
                    if isNew { known.append(addr); if known.count > 4 { known.removeFirst() } }
                    self.learned[s.dev] = known
                    // One device sends rising sequence numbers, from every address it uses. Numbers that jump back
                    // while the address changes mean a second device with the same name.
                    var duplicate = false
                    if let last = self.lastFrom[s.dev], last.addr != addr, s.seq + 3 < last.seq, now.timeIntervalSince(last.at) < 10 {
                        duplicate = now.timeIntervalSince(self.duplicateAt[s.dev] ?? .distantPast) > 60
                        if duplicate { self.duplicateAt[s.dev] = now }
                    }
                    self.lastFrom[s.dev] = (addr, s.seq, now)
                    self.learnedLock.unlock()
                    if isNew { Log.write("link: \(s.dev) is at \(String(cString: inet_ntoa(from.sin_addr)))") }
                    if duplicate {
                        Log.write("link: two devices are both named \(s.dev). Give each device its own name (mac, phone, tab)")
                        self.onDuplicate?(s.dev)
                    }
                    self.onStatus?(s)
                } else if let r = Proto.rejection(data, secret: self.secret, nowMs: Proto.nowMs()), r.dev != self.selfName {
                    self.learnedLock.lock()
                    let due = Date().timeIntervalSince(self.rejectedAt[r.dev] ?? .distantPast) > 60
                    if due { self.rejectedAt[r.dev] = Date() }
                    self.learnedLock.unlock()
                    if due {
                        Log.write("link: packets from \(r.dev) refused: \(r.reason == "clock" ? "its clock differs by more than 30 s" : "it uses a different secret, pair it again")")
                        self.onRejected?(r.dev, r.reason)
                    }
                }
            }
        }
        thread.name = "earhop-recv"
        thread.start()
    }

    /// The subnet broadcast address of every active IPv4 interface (en0 and friends). 255.255.255.255 alone only
    /// leaves through the primary interface.
    static func directedBroadcasts(port: UInt16) -> [Data] {
        var out: [Data] = []
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return out }
        defer { freeifaddrs(list) }
        var p: UnsafeMutablePointer<ifaddrs>? = first
        while let i = p {
            let f = Int32(i.pointee.ifa_flags)
            if f & IFF_UP != 0, f & IFF_BROADCAST != 0, f & IFF_LOOPBACK == 0,
               let a = i.pointee.ifa_addr, a.pointee.sa_family == sa_family_t(AF_INET), let b = i.pointee.ifa_dstaddr {
                var sin = b.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
                sin.sin_port = port.bigEndian
                out.append(withUnsafeBytes(of: &sin) { Data($0) })
            }
            p = i.pointee.ifa_next
        }
        return out
    }

    /// IP addresses of the other devices, learned from their signed packets.
    func peerHosts() -> [String] {
        learnedLock.lock(); defer { learnedLock.unlock() }
        return learned.values.flatMap { $0 }.compactMap { d -> String? in
            guard d.count >= MemoryLayout<sockaddr_in>.size else { return nil }
            var a = sockaddr_in()
            _ = withUnsafeMutableBytes(of: &a) { d.copyBytes(to: $0, count: MemoryLayout<sockaddr_in>.size) }
            return String(cString: inet_ntoa(a.sin_addr))
        }.reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }
    }

    /// The same addresses with the device each one belongs to.
    func peerHostsByDev() -> [(dev: String, host: String)] {
        learnedLock.lock(); defer { learnedLock.unlock() }
        return learned.flatMap { dev, list in list.compactMap { Discovery.ip($0).map { (dev: dev, host: $0) } } }
    }

    func send(_ s: Status) {
        guard sendFd >= 0, let data = Proto.encode(s, secret: secret) else { return }
        learnedLock.lock()
        let direct = learned.values.flatMap { $0 }
        learnedLock.unlock()
        for t in extraTargets() + direct + Link.directedBroadcasts(port: port) {
            t.withUnsafeBytes { tp in
                data.withUnsafeBytes { raw in
                    _ = sendto(sendFd, raw.baseAddress, data.count, 0, tp.bindMemory(to: sockaddr.self).baseAddress, socklen_t(t.count))
                }
            }
        }
        var a = addr(INADDR_BROADCAST)
        data.withUnsafeBytes { raw in
            withUnsafePointer(to: &a) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    _ = sendto(sendFd, raw.baseAddress, data.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }
}
