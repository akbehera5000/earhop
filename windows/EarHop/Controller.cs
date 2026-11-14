namespace EarHop.Win;

/// <summary>Runs the HandoffCore decisions on this PC: connect and release the earbuds, explicit Pause and Play, events
/// and logging (Controller.swift). Decisions are acted on only in live mode.</summary>
sealed class Controller
{
    public sealed class Peer { public Status Status; public DateTime SeenAt; public DateTime? LastPlayingAt; public DateTime? PlayingSince; public string Via = ""; }
    public sealed record PeerInfo(string Name, bool Playing, Ear Ear, bool Owner, bool Call, int Age, string Via);

    private Config cfg;
    public Config Cfg { get => cfg; set { cfg = value; Core.Settings = value.HandoffSettings(); } }
    public readonly HandoffCore Core;
    private readonly BtAudio bt;
    private readonly Dictionary<string, Peer> peers = new();
    private readonly object gate = new();
    private HashSet<string> knownPeers = new();
    private HandoffPhase? lastPhase;
    private string? lastHold;
    private bool wasOff;
    public string? Stage { get; private set; }
    public string? LastFailure { get; private set; }
    public DateTime PausedUntil = DateTime.MinValue;
    public bool AutoOff => !cfg.AutoSwitch || PausedUntil > DateTime.UtcNow;
    public event Action<string, string>? OnEvent; // (kind, text)
    public Func<string?> AutoGate = () => null;
    /// <summary>Brings up the Bluetooth link to the earbuds another way (the Buds' serial channel), for a claim whose
    /// connect request did not work. Returns true when the link came up.</summary>
    public Func<Task<bool>>? Nudge;
    /// <summary>Runs on the UI thread right before a release (closes the Buds status channel first).</summary>
    public Action? BeforeRelease;
    private readonly SynchronizationContext ui;

    public Controller(Config cfg, BtAudio bt)
    {
        this.cfg = cfg;
        this.bt = bt;
        Core = new HandoffCore(cfg.HandoffSettings());
        ui = SynchronizationContext.Current ?? new SynchronizationContext();
    }

    public string? HoldReason => AutoOff ? $"automatic switching is {(cfg.AutoSwitch ? "paused" : "off")}" : Core.HoldReason;

    // MARK: peers

    public void Receive(Status incoming, string via)
    {
        lock (gate)
        {
            var now = DateTime.UtcNow;
            if (incoming.V >= 4 && !ClockGuard.Shared.Check(incoming.Dev, incoming.Ts, Proto.NowMs(),
                    via == "ble" ? ClockGuard.BleTolerance : ClockGuard.WifiTolerance, incoming.Seq)) return;
            NameBook.Shared.Add(incoming.Dev);
            peers.TryGetValue(incoming.Dev, out var p);
            var prev = p?.Status;
            var order = PeerOrder.Accept(prev?.Seq, incoming.Seq, via == "ble",
                                         incoming.V >= 4 ? incoming.Ts : long.MaxValue, prev is { V: >= 4 } pv ? pv.Ts : long.MinValue);
            if (!order.Take) { if (p != null) p.SeenAt = now; return; }
            var s = incoming with { Seq = order.Seq };
            var last = p?.LastPlayingAt;
            var since = p?.PlayingSince;
            if (s.Playing) { last = now; since ??= now; } else since = null;
            if (prev is Status old && (old.Playing != s.Playing || old.Owner != s.Owner || old.Ear != s.Ear || old.Call != s.Call))
                Log.Write($"peer {s.Dev} ({via}): playing={(s.Playing ? 1 : 0)} ear={EarText.Raw(s.Ear)} owner={(s.Owner ? 1 : 0)} call={(s.Call ? 1 : 0)}");
            peers[s.Dev] = new Peer { Status = s, SeenAt = now, LastPlayingAt = last, PlayingSince = since, Via = via };
        }
    }

    public List<PeerInfo> PeerInfos()
    {
        lock (gate)
        {
            var now = DateTime.UtcNow;
            return peers.Values.Where(p => (now - p.SeenAt).TotalSeconds < 15).OrderBy(p => p.Status.Dev, StringComparer.Ordinal)
                .Select(p => new PeerInfo(p.Status.Dev, p.Status.Playing, p.Status.Ear, p.Status.Owner, p.Status.Call, (int)(now - p.SeenAt).TotalSeconds, p.Via))
                .ToList();
        }
    }

    private List<HandoffPeer> HandoffPeers()
    {
        lock (gate)
            return peers.Values.Select(p => new HandoffPeer(p.Status.Dev, p.Status.Playing, p.Status.Ear, p.Status.Owner, p.Status.Call,
                                                            p.SeenAt, p.PlayingSince, p.LastPlayingAt, p.Status.Att)).ToList();
    }

    /// <summary>Which earbuds the device that holds them reports (its "prof"), for the Automatic earbuds choice.</summary>
    public string? OwnerProfile()
    {
        lock (gate)
        {
            var now = DateTime.UtcNow;
            var p = peers.Values.FirstOrDefault(p => p.Status.Owner && (now - p.SeenAt).TotalSeconds < 10 && p.Status.Prof is "buds" or "airpods");
            return p?.Status.Prof;
        }
    }

    public string? OwnerPeer()
    {
        lock (gate)
        {
            var now = DateTime.UtcNow;
            return peers.Values.FirstOrDefault(p => p.Status.Owner && (now - p.SeenAt).TotalSeconds < 5)?.Status.Dev;
        }
    }

    // MARK: events and actions

    private void Emit(string kind, string text, bool dry = false)
    {
        Log.Write($"{(dry ? "DRY RUN " : "")}{kind}: {text}");
        ui.Post(_ => OnEvent?.Invoke(kind, text), null);
    }

    private void Run(List<HandoffAction> list)
    {
        foreach (var a in list)
        {
            switch (a)
            {
                case HandoffAction.Claim c: PerformClaim(c.Reason, c.Manual); break;
                case HandoffAction.Release r: PerformRelease(r.Reason); break;
                case HandoffAction.Pause p:
                    if (cfg.LiveMode) { Emit("pause", p.Reason); _ = MediaControl.Pause(p.Reason); } else Emit("pause", "would pause (" + p.Reason + ")", true);
                    break;
                case HandoffAction.Resume r:
                    if (cfg.LiveMode) { Emit("resume", r.Reason); _ = MediaControl.Resume(r.Reason); } else Emit("resume", "would resume (" + r.Reason + ")", true);
                    break;
                case HandoffAction.Info i: Emit("info", i.Text); break;
                case HandoffAction.SafeMode s:
                    Emit(s.On ? "safe" : "info", s.On ? $"Safe mode: auto claiming paused after {Core.Failures} failed switches" : "Auto claiming resumed");
                    break;
            }
        }
    }

    private void PerformClaim(string reason, bool manual)
    {
        string name = cfg.EarbudsShort;
        if (!cfg.LiveMode) { Emit("claim", $"would claim {name} ({reason})", true); return; }
        Emit("info", $"claiming {name} ({reason})");
        Task.Run(() =>
        {
            bool ok = false;
            string? why = null;
            try
            {
                if (!bt.Known())
                {
                    why = $"Windows does not know the {name}: pair them once in Bluetooth settings (name contains \"{bt.NameFilter}\")";
                }
                else
                {
                    if (!manual && OwnerPeer() is string holder)
                    {
                        Stage = $"Waiting for {holder} to let go";
                        double waited = 0;
                        while (waited < 8 && OwnerPeer() != null && !bt.Connected()) { Thread.Sleep(150); waited += 0.15; }
                        Log.Write($"claim: waited {waited:0.0} s for {holder} to let go");
                    }
                    var started = DateTime.UtcNow;
                    // First a plain connect request, given 6 s. Then, if the earbuds did not come, the link is brought up
                    // another way (the Buds' serial channel) and the request is sent again, with the full wait.
                    for (int attempt = 1; attempt <= 3 && !ok; attempt++)
                    {
                        if (bt.Connected()) { ok = true; break; }
                        Stage = "Connecting here";
                        if (attempt == 2 && Nudge != null)
                        {
                            bool up = false;
                            try { up = Nudge().GetAwaiter().GetResult(); } catch (Exception e) { Log.Write($"connect: link nudge failed: {e.Message}"); }
                            Log.Write($"connect: Bluetooth link {(up ? "opened through the Buds channel" : "could not be opened")}, asking again");
                        }
                        int accepted = bt.Connect();
                        Log.Write($"connect: attempt {attempt}, {accepted} endpoint(s) accepted the request");
                        if (accepted == 0)
                        {
                            Log.Write($"connect: the Bluetooth audio driver refused the connect request. Endpoints: {bt.Describe()}");
                            // Refused: no point waiting for it, bring the link up the other way at once.
                            if (attempt == 1 && Nudge != null) { Emit("info", "Connect request refused, opening the link another way"); continue; }
                        }
                        // The first request gets 4 s (Windows' own Connect takes about 2 to 4 s), later ones the full wait.
                        int waits = attempt == 1 ? 40 : cfg.IsBuds ? 150 : 80;
                        for (int i = 0; i < waits; i++)
                        {
                            Thread.Sleep(100);
                            if (i == 10) Stage = "Confirming connection";
                            if (bt.Connected()) { ok = true; break; }
                        }
                        if (!ok && attempt < 3) Log.Write($"connect: not connected yet after attempt {attempt}, trying again ({attempt + 1} of 3)");
                    }
                    if (ok) Log.Write($"claim: link up {(DateTime.UtcNow - started).TotalSeconds:0.0} s after connect started");
                    else why = OwnerPeer() is string h ? $"{h} is still connected to the {name}" : $"the {name} did not accept the connection. Connect them from Bluetooth settings";
                }
            }
            catch (Exception e) { why = e.Message; Log.Write($"claim: {e}"); }
            ui.Post(_ =>
            {
                Stage = null;
                LastFailure = ok ? null : why;
                Run(Core.ClaimFinished(ok, manual, why, DateTime.UtcNow));
                if (ok) Emit("claim", $"{name} connected ({reason})");
            }, null);
        });
    }

    private void PerformRelease(string reason)
    {
        string name = cfg.EarbudsShort;
        if (!cfg.LiveMode) { Emit("release", $"would release {name} ({reason})", true); return; }
        Stage = $"Releasing {name}";
        try { BeforeRelease?.Invoke(); } catch (Exception e) { Log.Write($"release: {e.Message}"); }
        Task.Run(() =>
        {
            var started = DateTime.UtcNow;
            int n = 0;
            try { n = bt.Disconnect(); } catch (Exception e) { Log.Write($"release: {e.Message}"); }
            bool still = true;
            for (int i = 0; i < 30 && still; i++) { Thread.Sleep(100); still = bt.Connected(); }
            double took = (DateTime.UtcNow - started).TotalSeconds;
            ui.Post(_ =>
            {
                Stage = null;
                Core.ReleaseFinished(DateTime.UtcNow);
                if (still) { LastFailure = $"the {name} are still connected to this PC"; Emit("info", $"release: {name} still connected after {took:0.0} s ({n} profiles asked)"); }
                else { LastFailure = null; Emit("release", $"{name} disconnected ({reason}) in {took:0.0} s"); }
            }, null);
        });
    }

    public void ClaimNow() => Run(Core.ManualClaim(DateTime.UtcNow));
    public void ReleaseNow() => Run(Core.ManualRelease(DateTime.UtcNow));
    public void ClearSafeMode() { Core.ClearSafeMode(); Emit("info", "Auto claiming resumed"); }

    /// <summary>Called four times a second on the UI thread.</summary>
    public void Tick(bool playing, EarReading ear, bool owner, bool inCall, bool attention)
    {
        var names = PeerInfos().Select(p => p.Name).ToHashSet();
        foreach (var n in names.Except(knownPeers)) Emit("peer", $"{n} joined");
        foreach (var n in knownPeers.Except(names)) Emit("peer", $"{n} went quiet");
        knownPeers = names;

        if (AutoOff != wasOff)
        {
            wasOff = AutoOff;
            Log.Write(AutoOff ? $"handoff: automatic switching {(cfg.AutoSwitch ? "paused" : "off")}" : "handoff: automatic switching on");
        }
        if (AutoOff) return;

        var input = new HandoffInput(DateTime.UtcNow, playing, ear, owner, inCall, HandoffPeers(), AutoGate(), false, attention);
        var list = Core.Tick(input);
        if (Core.Phase != lastPhase || list.Count > 0)
        {
            var acts = string.Join("; ", list.Select(a => a.ToString()));
            Log.Write($"sm: {(lastPhase is HandoffPhase lp ? PhaseText.Raw(lp) : "start")} -> {PhaseText.Raw(Core.Phase)} {Core.Describe(input, cfg.Earbuds)}"
                      + (acts.Length == 0 ? "" : $" actions=[{acts}]"));
            lastPhase = Core.Phase;
        }
        if (Core.HoldReason != lastHold)
        {
            if (Core.HoldReason is string h) Log.Write($"hold: not taking the {cfg.EarbudsShort}: {h}");
            lastHold = Core.HoldReason;
        }
        if (owner) LastFailure = null;
        Run(list);
    }
}
