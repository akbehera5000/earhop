using EarHop;

/// <summary>The deterministic simulator from mac/Sources/EarHop/SimTests.swift: several devices, one pair of earbuds,
/// a fake clock, a toggle Play/Pause key, audio that stays busy 2 s after a pause, slow disconnects.</summary>
sealed class HandoffSim
{
    public sealed class Dev
    {
        public readonly string Name;
        public readonly HandoffCore Core;
        public bool ToggleKeys;
        public bool PlayerOn;
        public DateTime StaleUntil = DateTime.MinValue;
        public bool StuckPlaying;
        public bool IgnoresPause;
        public DateTime DropUntil = DateTime.MinValue;
        public EarReading Ear = new(EarHop.Ear.Both, "sim", EarConfidence.High);
        public bool InCall;
        public string? Gate;
        public bool Online = true;
        public int Pauses, Resumes, Claims, Releases;
        public double SpeakerSeconds;
        public readonly Dictionary<string, (DateTime? Since, DateTime? Last)> Seen = new();
        public Dev(string name, bool toggle, HandoffSettings s) { Name = name; ToggleKeys = toggle; Core = new HandoffCore(s); }
    }

    public DateTime Now = DateTimeOffset.FromUnixTimeSeconds(1_700_000_000).UtcDateTime;
    public string? Holder;
    public readonly List<Dev> Devs = new();
    public static double DefaultConnect = 1.5;
    public static double DefaultDisconnect = 0.5;
    public double ConnectLatency = DefaultConnect;
    public double DisconnectLatency = DefaultDisconnect;
    public string? StrayTo;
    private readonly List<(DateTime At, Action Run)> timers = new();
    private string? lastHolder;

    public Dev Add(string name, bool toggle = false, Action<HandoffSettings>? tweak = null)
    {
        // The Swift HandoffSettings defaults: ear readings required (the simulator sets them).
        var s = new HandoffSettings { DeviceName = name, RequireEar = true };
        tweak?.Invoke(s);
        var d = new Dev(name, toggle, s);
        Devs.Add(d);
        return d;
    }

    public bool Playing(Dev d)
    {
        if (Now < d.DropUntil) return false;
        return d.StuckPlaying || d.PlayerOn || Now < d.StaleUntil;
    }

    private void Stop(Dev d) { if (d.PlayerOn) { d.PlayerOn = false; d.StaleUntil = Now.AddSeconds(2); } }
    public void UserPlay(Dev d) => d.PlayerOn = true;
    public void UserPause(Dev d) => Stop(d);
    private void After(double s, Action f) => timers.Add((Now.AddSeconds(s), f));

    private void Apply(Dev d, List<HandoffAction> actions)
    {
        foreach (var a in actions)
        {
            switch (a)
            {
                case HandoffAction.Pause:
                    d.Pauses++;
                    if (d.IgnoresPause) continue;
                    if (d.ToggleKeys && !d.PlayerOn) d.PlayerOn = true; else Stop(d);
                    break;
                case HandoffAction.Resume:
                    d.Resumes++;
                    if (d.ToggleKeys && d.PlayerOn) Stop(d); else d.PlayerOn = true;
                    break;
                case HandoffAction.Claim c:
                {
                    d.Claims++;
                    var start = Now;
                    void Attempt()
                    {
                        bool holderOnline = Devs.FirstOrDefault(x => x.Name == Holder)?.Online ?? false;
                        if (Holder == null || Holder == d.Name || !holderOnline || (Now - start).TotalSeconds >= 8 || c.Manual)
                            After(ConnectLatency, () =>
                            {
                                bool ok = Holder == null || Holder == d.Name;
                                if (ok) Holder = d.Name;
                                Apply(d, d.Core.ClaimFinished(ok, c.Manual, ok ? null : "held", Now));
                            });
                        else After(0.25, Attempt);
                    }
                    Attempt();
                    break;
                }
                case HandoffAction.Release:
                    d.Releases++;
                    After(DisconnectLatency, () =>
                    {
                        if (Holder == d.Name)
                        {
                            Holder = null;
                            if (StrayTo is string s) { StrayTo = null; After(0.5, () => { if (Holder == null) Holder = s; }); }
                        }
                        d.Core.ReleaseFinished(Now);
                    });
                    break;
            }
        }
    }

    public void Step()
    {
        var due = timers.Where(t => t.At <= Now).ToList();
        timers.RemoveAll(t => t.At <= Now);
        foreach (var t in due) t.Run();
        if (Holder != lastHolder && Devs.FirstOrDefault(x => x.Name == lastHolder) is Dev old && old.PlayerOn) old.DropUntil = Now.AddSeconds(0.75);
        lastHolder = Holder;
        var statuses = Devs.Select(x => (D: x, Playing: x.Core.AdvertisedPlaying(Playing(x)), Owner: Holder == x.Name)).ToList();
        foreach (var d in Devs.Where(x => x.Online))
        {
            var peers = new List<HandoffPeer>();
            foreach (var s in statuses.Where(s => s.D != d && s.D.Online))
            {
                var rec = d.Seen.TryGetValue(s.D.Name, out var r) ? r : (null, null);
                if (s.Playing) { rec.Last = Now; rec.Since ??= Now; } else rec.Since = null;
                d.Seen[s.D.Name] = rec;
                peers.Add(new HandoffPeer(s.D.Name, s.Playing, s.D.Ear.Ear, s.Owner, s.D.InCall, Now, rec.Since, rec.Last));
            }
            var input = new HandoffInput(Now, Playing(d), d.Ear, Holder == d.Name, d.InCall, peers, d.Gate);
            Apply(d, d.Core.Tick(input));
            if (d.PlayerOn && Holder != d.Name) d.SpeakerSeconds += 0.25;
        }
        Now = Now.AddSeconds(0.25);
    }

    public void Run(double seconds) { for (int i = 0; i < (int)(seconds * 4); i++) Step(); }

    /// <summary>The handoff timelines, the same as runHandoffSimTests in SimTests.swift.</summary>
    public static void RunAll(Action<string, bool> check)
    {
        { // 1. Mac owns and plays, the phone starts playing.
            var w = new HandoffSim(); var mac = w.Add("mac", true); var phone = w.Add("phone");
            w.Holder = "mac"; w.UserPlay(mac); w.Run(5);
            w.UserPlay(phone); w.Run(15);
            check("sim1 phone gets the earbuds", w.Holder == "phone");
            check("sim1 mac paused exactly once", mac.Pauses == 1 && !mac.PlayerOn);
            check("sim1 no mac speaker playback", mac.SpeakerSeconds < 1);
            w.UserPause(phone); w.Run(30);
            check("sim1 earbuds stay on the phone after its pause", w.Holder == "phone" && mac.Claims == 0);
        }
        { // 2. Phone owns and plays, the Mac starts playing.
            var w = new HandoffSim(); var mac = w.Add("mac", true); var phone = w.Add("phone");
            w.Holder = "phone"; w.UserPlay(phone); w.Run(5);
            w.UserPlay(mac); w.Run(15);
            check("sim2 mac gets the earbuds", w.Holder == "mac");
            check("sim2 phone paused", phone.Pauses == 1 && !phone.PlayerOn);
            check("sim2 mac still playing", mac.PlayerOn);
        }
        { // 3. The owner pauses and another device starts within two seconds.
            var w = new HandoffSim(); var mac = w.Add("mac", false); var phone = w.Add("phone");
            w.Holder = "mac"; w.UserPlay(mac); w.Run(5);
            w.UserPause(mac); w.Run(1); w.UserPlay(phone); w.Run(15);
            check("sim3 phone gets the earbuds", w.Holder == "phone");
            check("sim3 mac stays paused", !mac.PlayerOn && mac.SpeakerSeconds < 1);
        }
        { // 4. The old owner keeps reporting playing.
            var w = new HandoffSim(); var mac = w.Add("mac", true); var phone = w.Add("phone");
            w.Holder = "phone"; w.UserPlay(phone); w.Run(5);
            phone.StuckPlaying = true;
            w.UserPlay(mac); w.Run(15);
            check("sim4 mac gets the earbuds despite the stuck phone", w.Holder == "mac");
            w.Run(60);
            check("sim4 stuck phone never takes them back", w.Holder == "mac" && phone.Claims == 0);
        }
        { // 5 and 6. Bluetooth disconnect takes 7 s.
            var w = new HandoffSim { DisconnectLatency = 7 }; var mac = w.Add("mac", true); var phone = w.Add("phone");
            w.Holder = "mac"; w.UserPlay(mac); w.Run(5);
            w.UserPlay(phone); w.Run(25);
            check("sim5 slow disconnect still hands over", w.Holder == "phone");
            check("sim5 no failed claim on the phone", phone.Core.Failures == 0 && !phone.Core.SafeMode);
            check("sim5 mac paused once", mac.Pauses == 1 && !mac.PlayerOn);
        }
        { // 7. The holder disappears and never lets go.
            var w = new HandoffSim(); var mac = w.Add("mac", true); var phone = w.Add("phone");
            w.Holder = "mac"; mac.Online = false;
            w.UserPlay(phone); w.Run(90);
            check("sim7 failed claims stop in safe mode", phone.Core.SafeMode && phone.Claims == 3);
            phone.Core.ManualClaim(w.Now);
            check("sim7 manual take still allowed in safe mode", phone.Core.Pending == HandoffCore.PendingKind.Claim);
        }
        { // 7b. Safe mode ends by itself after two minutes.
            var w = new HandoffSim(); var mac = w.Add("mac", true); var phone = w.Add("phone");
            w.Holder = "mac"; mac.Online = false;
            w.UserPlay(phone); w.Run(90);
            check("sim7b in safe mode first", phone.Core.SafeMode);
            w.Run(120);
            check("sim7b safe mode ends by itself and claims resume", phone.Claims > 3);
        }
        { // 7c. Safe mode ends at once when a device has the earbuds again.
            var w = new HandoffSim(); var mac = w.Add("mac", true); var phone = w.Add("phone");
            w.Holder = "mac"; mac.Online = false;
            w.UserPlay(phone); w.Run(90);
            mac.Online = true;
            w.Run(3);
            check("sim7c safe mode ends when the earbuds are seen again", !phone.Core.SafeMode);
        }
        { // 9. A call on the phone while the Mac owns the earbuds.
            var w = new HandoffSim(); var mac = w.Add("mac", true); var phone = w.Add("phone", false, s => s.CallNeedsPlaying = false);
            w.Holder = "mac"; w.UserPlay(mac); w.Run(5);
            phone.InCall = true; w.Run(15);
            check("sim9 the call takes the earbuds", w.Holder == "phone");
            check("sim9 mac paused for the call", mac.Pauses == 1 && !mac.PlayerOn);
        }
        { // 10. Manual Take while automatic switching is gated.
            var w = new HandoffSim(); var mac = w.Add("mac", true);
            mac.Gate = "quiet hours"; w.UserPlay(mac); w.Run(5);
            check("sim10 gate holds automatic claims", mac.Claims == 0);
            mac.Core.ManualClaim(w.Now);
            check("sim10 manual take works while gated", mac.Core.Pending == HandoffCore.PendingKind.Claim);
        }
        { // 11. An earbud comes out at the same moment the phone takes over.
            var w = new HandoffSim(); var mac = w.Add("mac", true); var phone = w.Add("phone");
            w.Holder = "mac"; w.UserPlay(mac); w.Run(5);
            w.UserPlay(phone); w.Run(0.25);
            mac.Ear = mac.Ear with { Ear = Ear.None }; w.Run(15);
            check("sim11 single pause during removal and handoff", mac.Pauses == 1 && !mac.PlayerOn);
            check("sim11 phone gets the earbuds", w.Holder == "phone");
        }
        { // 12. Quick Connect moves the earbuds without EarHop.
            var w = new HandoffSim(); var mac = w.Add("mac", true); w.Add("phone");
            w.Holder = "mac"; w.UserPlay(mac); w.Run(5);
            w.Holder = "phone"; w.Run(20);
            check("sim12 mac pauses once when the earbuds vanish", mac.Pauses == 1 && !mac.PlayerOn);
            check("sim12 mac does not grab them back", w.Holder == "phone" && mac.Claims == 0);
        }
        { // 13. The earbuds reconnect by themselves to an idle third device during a handoff.
            var w = new HandoffSim(); var mac = w.Add("mac", true); w.Add("phone"); var tab = w.Add("tab");
            w.Holder = "mac"; w.UserPlay(mac); w.Run(5);
            w.StrayTo = "tab";
            w.UserPlay(w.Devs[1]); w.Run(25);
            check("sim13 the tablet hands on earbuds it did not ask for", tab.Releases == 1 && tab.Claims == 0);
            check("sim13 the phone gets the earbuds", w.Holder == "phone");
            check("sim13 nobody else pauses", mac.Pauses == 1 && tab.Pauses == 0);
        }
        { // 14. Connecting the earbuds by hand to an idle device is kept.
            var w = new HandoffSim(); var phone = w.Add("phone"); var tab = w.Add("tab");
            phone.Gate = "quiet hours";
            w.UserPlay(phone); w.Run(10);
            w.Holder = "tab"; w.Run(20);
            check("sim14 a manual connect is not undone", w.Holder == "tab" && tab.Releases == 0);
        }
        { // Ear removal pause and resume; low confidence readings never act.
            var w = new HandoffSim(); var mac = w.Add("mac", true);
            w.Holder = "mac"; w.UserPlay(mac); w.Run(3);
            mac.Ear = mac.Ear with { Ear = Ear.One }; w.Run(2);
            check("ear pause on one out", mac.Pauses == 1 && !mac.PlayerOn);
            mac.Ear = mac.Ear with { Ear = Ear.Both }; w.Run(2);
            check("ear resume when back", mac.Resumes == 1 && mac.PlayerOn);
            mac.Ear = new EarReading(Ear.None, "last-known", EarConfidence.Low); w.Run(3);
            check("low confidence reading does not pause", mac.Pauses == 1 && mac.PlayerOn);
            mac.Ear = new EarReading(Ear.Both, "assumed", EarConfidence.Low); w.Run(3);
            check("low confidence reading does not resume", mac.Resumes == 1);
        }
        { // A pause right before the earbuds leave must not be followed by a second toggle.
            var w = new HandoffSim(); var mac = w.Add("mac", true);
            w.Holder = "mac"; w.UserPlay(mac); w.Run(3);
            mac.Ear = mac.Ear with { Ear = Ear.None }; w.Run(0.5);
            w.Holder = null; w.Run(5);
            check("no second pause after losing earbuds", mac.Pauses == 1 && !mac.PlayerOn);
        }
        { // The old owner's pause has no effect: the new owner keeps the earbuds.
            var w = new HandoffSim(); var mac = w.Add("mac", false); var phone = w.Add("phone");
            w.Holder = "mac"; w.UserPlay(mac); w.Run(5);
            mac.IgnoresPause = true;
            w.UserPlay(phone); w.Run(30);
            check("pingpong phone keeps the earbuds", w.Holder == "phone" && phone.Releases == 0);
            check("pingpong mac does not take them back", mac.Claims == 0);
        }
        { // A flickering peer never counts as a steady new start.
            var w = new HandoffSim(); var mac = w.Add("mac", false); var phone = w.Add("phone");
            w.Holder = "mac"; w.UserPlay(mac); w.Run(5);
            for (int i = 0; i < 20; i++) { w.UserPlay(phone); w.Run(0.5); phone.PlayerOn = false; phone.StaleUntil = DateTime.MinValue; w.Run(0.5); }
            check("flickering peer does not take the earbuds", w.Holder == "mac" && mac.Releases == 0);
        }
        { // Priority breaks a near tie only.
            var w = new HandoffSim(); var mac = w.Add("mac", true); var phone = w.Add("phone");
            w.UserPlay(mac); w.UserPlay(phone); w.Run(10);
            check("tie goes to the higher priority device", w.Holder == "mac");
        }
        { // Windows: the PC joins as a fourth device with no ear reading; the phone owns, the PC starts playing.
            var w = new HandoffSim(); var phone = w.Add("phone");
            var pc = w.Add("pc", false, s => s.RequireEar = false);
            pc.Ear = EarReading.Unknown;
            w.Holder = "phone"; w.UserPlay(phone); w.Run(5);
            w.UserPlay(pc); w.Run(15);
            check("pc takes the earbuds without an ear reading", w.Holder == "pc");
            check("pc: phone paused once", phone.Pauses == 1 && !phone.PlayerOn);
            w.UserPlay(phone); w.Run(15);
            check("pc lets go when the phone starts again", w.Holder == "phone" && pc.Pauses == 1);
        }
    }
}
