namespace EarHop;

/// <summary>Pure parser for the AirPods proximity pairing beacon (Beacon.swift). `md` includes the 2 byte company id
/// (0x4C 0x00, Apple). Type 0x07; model little endian at bytes 5..6; status byte 7 bits 0x02 and 0x08 are the two in-ear
/// flags; battery of the buds in tenths at byte 8, the case at the low nibble of byte 9.</summary>
public sealed record Beacon(ushort Model, Ear Ear, int? BudA, int? BudB, int? CaseLevel)
{
    public static Beacon? Parse(byte[] md)
    {
        if (md.Length < 9 || md[0] != 0x4C || md[1] != 0x00 || md[2] != 0x07) return null;
        ushort model = (ushort)(md[6] << 8 | md[5]);
        int status = md[7];
        int n = ((status & 0x02) != 0 ? 1 : 0) + ((status & 0x08) != 0 ? 1 : 0);
        var ear = n == 2 ? Ear.Both : n == 1 ? Ear.One : Ear.None;
        static int? Level(int v) => v <= 10 ? v : null;
        int? c = md.Length > 9 ? Level(md[9] & 0x0F) : null;
        return new Beacon(model, ear, Level(md[8] >> 4), Level(md[8] & 0x0F), c);
    }

    /// <summary>Your own AirPods are the strongest signal nearby. Only sources within 8 dB of the strongest (smoothed)
    /// count, so a weaker pair (someone else's) cannot change the reading. Among those the lowest in-ear count wins,
    /// because your earbuds can broadcast from more than one address and a removal shows on one first.</summary>
    public static int? Pick(IEnumerable<(int Count, double Rssi)> sources)
    {
        var list = sources.ToList();
        if (list.Count == 0) return null;
        double top = list.Max(s => s.Rssi);
        return list.Where(s => s.Rssi >= top - 8).Min(s => s.Count);
    }

    /// <summary>The AirPods models EarHop follows by default (Config.swift models): AirPods Pro, AirPods Pro 2 (Lightning
    /// and USB-C).</summary>
    public static readonly HashSet<ushort> Models = new() { 0x2014, 0x2924, 0x2024 };
}
