import Foundation

/// Bonjour (mDNS) discovery. Publishes this device and resolves other EarHop devices so status
/// can also be sent straight to them (unicast), which works on networks that drop broadcasts.
final class Discovery: NSObject, NetServiceDelegate, NetServiceBrowserDelegate {
    static let type = "_earhop._udp."
    private let name: String
    private let port: Int32
    private var published: NetService?
    private let browser = NetServiceBrowser()
    private var resolving: [String: NetService] = [:]
    private let lock = NSLock()
    private var addresses: [String: [Data]] = [:]
    var onChange: (([String]) -> Void)?

    init(name: String, port: Int) {
        self.name = name
        self.port = Int32(port)
        super.init()
    }

    func start() {
        let svc = NetService(domain: "", type: Discovery.type, name: name, port: port)
        svc.delegate = self
        svc.publish()
        published = svc
        browser.delegate = self
        browser.searchForServices(ofType: Discovery.type, inDomain: "")
    }

    /// sockaddr blobs of every resolved peer, ready for sendto.
    var targets: [Data] {
        lock.lock(); defer { lock.unlock() }
        return addresses.values.flatMap { $0 }
    }

    /// IP addresses of the other EarHop devices found by Bonjour.
    var hosts: [String] {
        targets.compactMap { d -> String? in
            guard d.count >= MemoryLayout<sockaddr_in>.size else { return nil }
            var a = sockaddr_in()
            _ = withUnsafeMutableBytes(of: &a) { d.copyBytes(to: $0, count: MemoryLayout<sockaddr_in>.size) }
            return String(cString: inet_ntoa(a.sin_addr))
        }
    }

    /// The same addresses with the device name each one was published under.
    var hostsByDev: [(dev: String, host: String)] {
        lock.lock(); defer { lock.unlock() }
        return addresses.flatMap { dev, list in list.compactMap { Discovery.ip($0).map { (dev: dev, host: $0) } } }
    }

    static func ip(_ d: Data) -> String? {
        guard d.count >= MemoryLayout<sockaddr_in>.size else { return nil }
        var a = sockaddr_in()
        _ = withUnsafeMutableBytes(of: &a) { d.copyBytes(to: $0, count: MemoryLayout<sockaddr_in>.size) }
        return String(cString: inet_ntoa(a.sin_addr))
    }

    var names: [String] {
        lock.lock(); defer { lock.unlock() }
        return addresses.keys.sorted()
    }

    func netServiceBrowser(_ b: NetServiceBrowser, didFind svc: NetService, moreComing: Bool) {
        guard svc.name != name else { return }
        svc.delegate = self
        resolving[svc.name] = svc
        svc.resolve(withTimeout: 8)
    }

    func netServiceBrowser(_ b: NetServiceBrowser, didRemove svc: NetService, moreComing: Bool) {
        lock.lock(); addresses[svc.name] = nil; lock.unlock()
        resolving[svc.name] = nil
        DispatchQueue.main.async { self.onChange?(self.names) }
    }

    func netServiceDidResolveAddress(_ svc: NetService) {
        // IPv4 only, the link uses an IPv4 socket.
        let v4 = (svc.addresses ?? []).filter { $0.count >= MemoryLayout<sockaddr_in>.size && $0[1] == UInt8(AF_INET) }
        guard !v4.isEmpty else { return }
        lock.lock(); addresses[svc.name] = v4; lock.unlock()
        Log.write("discovery: found \(svc.name)")
        DispatchQueue.main.async { self.onChange?(self.names) }
    }

    func netService(_ svc: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolving[svc.name] = nil
    }
}
