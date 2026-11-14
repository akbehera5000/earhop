namespace EarHop;

/// <summary>Galaxy Buds serial protocol (port of BudsProtocol in BudsWear.swift): framing, wear status, battery and the
/// controls EarHop uses. Byte positions follow the open source omarchy-galaxy-buds-pro project (MIT licensed), which
/// documents them per model. Frame: 0xFD, 2 length bytes (low 10 bits = id + payload + crc), id, payload, 2 crc bytes
/// (CRC16 XModem, little endian), 0xDD.</summary>
public static class BudsProtocol
{
    public const int IdStatus = 0x60, IdExtended = 0x61, IdAck = 66, IdNoiseUpdate = 119, IdNoise = 120, IdEqualizer = 134, IdLockTouchpad = 144;
    public static readonly string[] NoiseModes = { "off", "anc", "ambient" };
    public static readonly string[] EqPresets = { "off", "bass", "soft", "dynamic", "clear", "treble" };
    public const int MaxSize = 600;

    public readonly record struct Msg(int Id, byte[] Payload);

    public sealed record State
    {
        public int Revision { get; init; } = -1;
        public int BattL { get; init; } = -1;
        public int BattR { get; init; } = -1;
        public int BattCase { get; init; } = -1;
        public string PlaceL { get; init; } = "unknown";
        public string PlaceR { get; init; } = "unknown";
        public bool ChargeL { get; init; }
        public bool ChargeR { get; init; }
        public bool ChargeCase { get; init; }
        public string Noise { get; init; } = "unknown";
        public string Eq { get; init; } = "unknown";
        public bool? TouchOn { get; init; }
        public bool Tap { get; init; }
        public bool Double { get; init; }
        public bool Triple { get; init; }
        public bool Hold { get; init; }
        public bool DoubleCall { get; init; }
        public bool HoldCall { get; init; }
        public int Wearing => (PlaceL == "wearing" ? 1 : 0) + (PlaceR == "wearing" ? 1 : 0);
        public Ear Ear => Wearing == 2 ? Ear.Both : Wearing == 1 ? Ear.One : Ear.None;
    }

    private static readonly ushort[] Table = Enumerable.Range(0, 256).Select(i =>
    {
        ushort c = (ushort)(i << 8);
        for (int k = 0; k < 8; k++) c = (c & 0x8000) != 0 ? (ushort)((c << 1) ^ 0x1021) : (ushort)(c << 1);
        return c;
    }).ToArray();

    public static ushort Crc16(IEnumerable<byte> data)
    {
        ushort crc = 0;
        foreach (var b in data) crc = (ushort)((crc << 8) ^ Table[((crc >> 8) ^ b) & 0xFF]);
        return crc;
    }

    public static byte[] Encode(int id, params byte[] payload)
    {
        int size = 1 + payload.Length + 2;
        var body = new[] { (byte)id }.Concat(payload).ToArray();
        ushort crc = Crc16(body);
        return new byte[] { 0xFD, (byte)(size & 0xFF), (byte)(size >> 8) }.Concat(body).Concat(new[] { (byte)(crc & 0xFF), (byte)(crc >> 8), (byte)0xDD }).ToArray();
    }

    /// <summary>Checksum bookkeeping for one connection: bad checksums are dropped only once a frame has passed, so a
    /// wrong assumption about the checksum can never silence the Buds.</summary>
    public sealed class CrcStats { public int Good, Bad; public bool Enforcing => Good > 0; }

    public static bool CrcOk(int id, byte[] payload, byte lo, byte hi)
    {
        ushort c = Crc16(new[] { (byte)id }.Concat(payload));
        return c == (lo | hi << 8) || c == (hi | lo << 8);
    }

    /// <summary>Takes complete frames off the front of buf.</summary>
    public static List<Msg> Extract(List<byte> buf, CrcStats? crc = null)
    {
        var outList = new List<Msg>();
        while (true)
        {
            int start = buf.IndexOf(0xFD);
            if (start < 0) { buf.Clear(); break; }
            if (start > 0) buf.RemoveRange(0, start);
            if (buf.Count < 4) break;
            int size = (buf[1] | buf[2] << 8) & 0x3FF;
            if (size < 3 || size > MaxSize) { buf.RemoveAt(0); continue; }
            int total = 4 + size;
            if (buf.Count < total) break;
            if (buf[total - 1] != 0xDD) { buf.RemoveAt(0); continue; }
            int id = buf[3];
            var payload = buf.GetRange(4, size - 3).ToArray();
            if (crc != null)
            {
                if (CrcOk(id, payload, buf[total - 3], buf[total - 2])) crc.Good++;
                else
                {
                    crc.Bad++;
                    if (crc.Enforcing) { buf.RemoveAt(0); continue; }
                }
            }
            outList.Add(new Msg(id, payload));
            buf.RemoveRange(0, total);
        }
        return outList;
    }

    public static byte[] NoiseCommand(string mode) => Encode(IdNoise, (byte)Math.Max(0, Array.IndexOf(NoiseModes, mode)));
    public static byte[] EqCommand(string preset) => Encode(IdEqualizer, (byte)Math.Max(0, Array.IndexOf(EqPresets, preset)));
    public static byte[] TouchCommand(bool on, State s)
    {
        var g = new[] { s.Tap, s.Double, s.Triple, s.Hold };
        if (on && !g.Contains(true)) g = new[] { true, true, true, true };
        return Encode(IdLockTouchpad, new[] { (byte)(on ? 1 : 0) }.Concat(g.Select(x => (byte)(x ? 1 : 0))).Concat(new[] { (byte)(s.DoubleCall ? 1 : 0), (byte)(s.HoldCall ? 1 : 0) }).ToArray());
    }

    private static string Place(int n) => n switch { 0 => "disconnected", 1 => "wearing", 2 => "idle", 3 or 4 => "case", _ => "unknown" };
    private static int Battery(int v) => v is >= 1 and <= 100 ? v : -1;
    private static string EqName(int v) => v < EqPresets.Length ? EqPresets[v] : v == 6 ? "custom" : "unknown";

    /// <summary>Applies one message to the state and returns the new state.</summary>
    public static State Apply(Msg m, State s)
    {
        var p = m.Payload.Select(b => (int)b).ToArray();
        switch (m.Id)
        {
            case IdExtended when p.Length > 12:
            {
                int t = p[10];
                return s with
                {
                    Revision = p[0], BattL = Battery(p[2]), BattR = Battery(p[3]), PlaceL = Place(p[6] >> 4), PlaceR = Place(p[6] & 15),
                    BattCase = Battery(p[7]), Eq = EqName(p[9]), TouchOn = (t & 0x80) != 0, Tap = (t & 0x08) != 0, Double = (t & 0x04) != 0,
                    Triple = (t & 0x02) != 0, Hold = (t & 0x01) != 0, DoubleCall = (t & 0x10) != 0, HoldCall = (t & 0x20) != 0,
                    Noise = p[12] < NoiseModes.Length ? NoiseModes[p[12]] : "unknown",
                };
            }
            case IdStatus when p.Length > 6:
            {
                var n = s with { BattL = Battery(p[1]), BattR = Battery(p[2]), PlaceL = Place(p[5] >> 4), PlaceR = Place(p[5] & 15), BattCase = Battery(p[6]) };
                if (p.Length > 7)
                {
                    int c = p[7];
                    n = n with { ChargeL = (c & 0x10) != 0, ChargeR = (c & 0x04) != 0, ChargeCase = (c & 0x01) != 0 };
                    if (n.ChargeL) n = n with { PlaceL = "case" };
                    if (n.ChargeR) n = n with { PlaceR = "case" };
                }
                return n;
            }
            case IdNoiseUpdate when p.Length > 0:
                return s with { Noise = p[0] < NoiseModes.Length ? NoiseModes[p[0]] : "unknown" };
            case IdAck when p.Length >= 2:
                int v = p[1];
                return p[0] switch
                {
                    IdNoise when v < NoiseModes.Length => s with { Noise = NoiseModes[v] },
                    IdLockTouchpad => s with { TouchOn = v == 1 },
                    IdEqualizer => s with { Eq = EqName(v) },
                    _ => s,
                };
            default:
                return s;
        }
    }
}
