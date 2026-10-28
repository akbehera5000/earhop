import Foundation

/// Pure parser for the AirPods proximity pairing beacon. `md` includes the 2 byte company id.
struct Beacon {
    let model: UInt16
    let ear: Ear
    /// Battery of the two buds in tenths (0...10), nil when not reported. Order depends on which bud is primary.
    let budA: Int?
    let budB: Int?
    /// Charging case battery in tenths, nil when the case is not reported.
    let caseLevel: Int?

    /// Your own AirPods are the strongest signal nearby. Only sources within 8 dB of the strongest (smoothed) signal
    /// count, so a weaker pair (someone else's, in their case or ears) cannot change the reading. Among those the lowest
    /// in-ear count wins, because your earbuds can broadcast from more than one address and a removal shows on one first.
    static func pick(_ sources: [(count: Int, rssi: Double)]) -> Int? {
        guard let top = sources.map({ $0.rssi }).max() else { return nil }
        return sources.filter { $0.rssi >= top - 8 }.map { $0.count }.min()
    }

    static func parse(_ md: Data) -> Beacon? {
        let b = [UInt8](md)
        guard b.count >= 9, b[0] == 0x4C, b[1] == 0x00, b[2] == 0x07 else { return nil }
        let model = (UInt16(b[6]) << 8) | UInt16(b[5])
        let status = b[7]
        var n = 0
        if status & 0x02 != 0 { n += 1 }
        if status & 0x08 != 0 { n += 1 }
        let ear: Ear = n == 2 ? .both : (n == 1 ? .one : .none)
        func level(_ v: UInt8) -> Int? { v <= 10 ? Int(v) : nil }
        let c: Int? = b.count > 9 ? level(b[9] & 0x0F) : nil
        return Beacon(model: model, ear: ear, budA: level(b[8] >> 4), budB: level(b[8] & 0x0F), caseLevel: c)
    }
}
