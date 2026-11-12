namespace EarHop;

// The handoff rules, ported line by line from mac/Sources/EarHop/HandoffCore.swift (and HandoffCore.kt). Any change to
// the rules goes into all three cores and all three simulators (AGENTS.md section 3).

public enum EarConfidence { High, Medium, Low }

/// <summary>One ear reading plus where it came from: buds-rfcomm, airpods-beacon, last-known, assumed or none.</summary>
public readonly record struct EarReading(Ear Ear, string Source, EarConfidence Confidence)
{
    public static readonly EarReading Unknown = new(Ear.Unknown, "none", EarConfidence.Low);
    public bool Trusted => Confidence != EarConfidence.Low && Ear != Ear.Unknown;
    public string Summary => $"{EarText.Raw(Ear)}/{Source}/{Confidence.ToString().ToLowerInvariant()}";
}

public enum HandoffPhase
{
    Idle, OwnerIdle, PlayingWithoutEarbuds, ClaimPending, OwnerPlaying, OwnerPausedByEarRemoval,
    ReleasePending, LostOwnership, CallPriority, SafeMode,
}

public static class PhaseText
{
    public static string Label(HandoffPhase p) => p switch
    {
        HandoffPhase.Idle => "Ready",
        HandoffPhase.OwnerIdle => "Connected, paused",
        HandoffPhase.PlayingWithoutEarbuds => "Playing without earbuds",
        HandoffPhase.ClaimPending => "Switching here",
        HandoffPhase.OwnerPlaying => "Playing",
        HandoffPhase.OwnerPausedByEarRemoval => "Paused, earbud out",
        HandoffPhase.ReleasePending => "Letting go",
        HandoffPhase.LostOwnership => "Earbuds moved away",
        HandoffPhase.CallPriority => "Call priority",
        _ => "Safe mode",
    };

    /// <summary>The Swift raw value (idle, ownerIdle, ...) for the log.</summary>
    public static string Raw(HandoffPhase p) { var s = p.ToString(); return char.ToLowerInvariant(s[0]) + s[1..]; }
}

public abstract record HandoffAction
{
    public sealed record Claim(string Reason, bool Manual) : HandoffAction;
    public sealed record Release(string Reason) : HandoffAction;
    public sealed record Pause(string Reason) : HandoffAction;
    public sealed record Resume(string Reason) : HandoffAction;
    public sealed record Info(string Text) : HandoffAction;
    public sealed record SafeMode(bool On) : HandoffAction;
}

public sealed record HandoffPeer(string Name, bool Playing, Ear Ear, bool Owner, bool Call, DateTime SeenAt,
                                 DateTime? PlayingSince, DateTime? LastPlayingAt, bool Att = false);

public sealed class HandoffSettings
{
    public string DeviceName = "pc";
    public bool FollowAttention = false;
    public List<string> Priority = new() { "mac", "tab", "phone" };
    public bool Live = true;
    public double Cooldown = 2;
    public double MinPlay = 1;
    public double Quiet = 2;
    public double ReleaseAfterOut = 120;
    public double ResumeWithin = 120;
    public bool PauseWhenOneOut = true;
    public int SafeModeAfter = 3;
    public double SafeModeSeconds = 120;
    /// <summary>When false, an unknown ear state counts as "in" for claims.</summary>
    public bool RequireEar = true;
    /// <summary>A busy microphone counts as a call only while this device also plays.</summary>
    public bool CallNeedsPlaying = true;
}

public sealed record HandoffInput(DateTime Now, bool Playing, EarReading Ear, bool Owner, bool InCall, List<HandoffPeer> Peers,
                                  string? Gate, bool OnSpeaker = false, bool Attention = false);

/// <summary>Pause and resume on ear removal (EarPolicy in HandoffCore.swift). Only trusted readings count.</summary>
public sealed class EarPolicy
{
    public enum Act { None, Pause, RemovedAlreadyStopped, RemovedNotPlaying, Resume, AlreadyPlaying, Expired }

    public Ear LastKnown = Ear.Unknown;
    public DateTime LastKnownAt = DateTime.MinValue;
    public DateTime LastPlayingAt = DateTime.MinValue;
    public DateTime? PausedAt;
    public bool PausedFromNone = true;
    public bool PausedByKey;

    public bool Waiting => PausedAt != null;
    public void Clear() => PausedAt = null;

    public static bool IsOut(Ear e, bool pauseOnOne) => e == Ear.None || (pauseOnOne && e == Ear.One);

    public Act Update(DateTime now, EarReading reading, bool playing, bool owner, bool pauseOnOne, double resumeWithin)
    {
        if (playing) LastPlayingAt = now;
        var ear = reading.Trusted ? reading.Ear : Ear.Unknown;
        var was = LastKnown; var wasAt = LastKnownAt;
        if (ear != Ear.Unknown) { LastKnown = ear; LastKnownAt = now; }
        if (!owner) return Act.None;
        bool Out(Ear e) => IsOut(e, pauseOnOne);
        bool becameNone = ear == Ear.None && was != Ear.None && was != Ear.Unknown;
        if (was != Ear.Unknown && ear != Ear.Unknown && (now - wasAt).TotalSeconds < 30
            && ((!Out(was) && Out(ear)) || (becameNone && PausedAt == null)))
        {
            bool playedRecently = playing || (now - LastPlayingAt).TotalSeconds < 6;
            if (!playedRecently) return Act.RemovedNotPlaying;
            PausedAt = now;
            PausedFromNone = ear == Ear.None;
            PausedByKey = playing;
            return playing ? Act.Pause : Act.RemovedAlreadyStopped;
        }
        if (PausedAt is DateTime at)
        {
            if ((now - at).TotalSeconds > resumeWithin) { PausedAt = null; return Act.Expired; }
            if (ear == Ear.None) PausedFromNone = true;
            bool back = PausedFromNone ? (ear == Ear.One || ear == Ear.Both) : ear == Ear.Both;
            if (back)
            {
                PausedAt = null;
                bool trustKey = PausedByKey;
                PausedByKey = false;
                return (!playing || trustKey) ? Act.Resume : Act.AlreadyPlaying;
            }
        }
        return Act.None;
    }
}

/// <summary>The handoff rule as a pure state machine (HandoffCore in HandoffCore.swift). Newest playback wins;
/// priority only breaks a near tie; calls override; a device that let go may not take the earbuds back until its own
/// audio has really stopped.</summary>
public sealed class HandoffCore
{
    public enum PendingKind { Claim, Release }

    public HandoffSettings Settings;
    public HandoffPhase Phase { get; private set; } = HandoffPhase.Idle;
    public PendingKind? Pending { get; private set; }
    public bool SafeMode { get; private set; }
    public int Failures { get; private set; }
    public string? HoldReason { get; private set; }
    public bool ClaimBlocked { get; private set; }
    public EarPolicy Ears { get; } = new();

    private DateTime? playingSince;
    private DateTime lastLocalPlayingAt = DateTime.MinValue;
    private DateTime? earOutSince;
    private DateTime lastHandoff = DateTime.MinValue;
    private bool prevOwner;
    private DateTime blockedAt = DateTime.MinValue;
    private DateTime lastPauseAt = DateTime.MinValue;
    private DateTime lastReleaseAt = DateTime.MinValue;
    private DateTime lostAt = DateTime.MinValue;
    private DateTime? ownerSince;
    private string? yieldedForCall;
    private DateTime gateLoggedAt = DateTime.MinValue;
    private bool inCallPhase;
    private DateTime claimStartedAt = DateTime.MinValue;
    private bool strayOwner;
    private HashSet<string> peerOwners = new();
    private DateTime peerLetGoAt = DateTime.MinValue;
    private DateTime safeSince = DateTime.MinValue;

    public HandoffCore(HandoffSettings settings) { Settings = settings; }

    private static double Sec(DateTime a, DateTime b) => (a - b).TotalSeconds;

    private int Prio(string n) { int i = Settings.Priority.IndexOf(n); return i < 0 ? int.MaxValue : i; }

    private bool EarIn(EarReading r) => r.Ear == Ear.Both || r.Ear == Ear.One || (!Settings.RequireEar && r.Ear == Ear.Unknown);

    private double CooldownNow => Settings.Cooldown * Math.Pow(2, Math.Min(Failures, 3));

    private void Block(DateTime now) { ClaimBlocked = true; blockedAt = now; }

    private List<HandoffAction> StartClaim(string reason, bool manual, DateTime now)
    {
        if (Pending != null) return new();
        lastHandoff = now;
        claimStartedAt = now;
        if (Settings.Live) Pending = PendingKind.Claim;
        return new() { new HandoffAction.Claim(reason, manual) };
    }

    private List<HandoffAction> StartRelease(string reason, DateTime now)
    {
        if (Pending != null) return new();
        lastHandoff = now;
        if (Settings.Live) { Pending = PendingKind.Release; Block(now); lastReleaseAt = now; }
        return new() { new HandoffAction.Release(reason) };
    }

    private HandoffAction DoPause(string reason, DateTime now) { lastPauseAt = now; return new HandoffAction.Pause(reason); }

    private List<HandoffAction> PauseBeforeRelease(string reason, DateTime now)
    {
        if (Sec(now, lastPauseAt) < 6) return new() { new HandoffAction.Info("already paused by EarHop, letting go without a second key") };
        return new() { DoPause(reason, now) };
    }

    public List<HandoffAction> ManualClaim(DateTime now) => StartClaim("manual", true, now);
    public List<HandoffAction> ManualRelease(DateTime now) => StartRelease("manual", now);

    public List<HandoffAction> ClaimFinished(bool ok, bool manual, string? failure, DateTime now)
    {
        if (Pending == PendingKind.Claim) Pending = null;
        lastHandoff = now;
        if (ok)
        {
            Failures = 0;
            ClaimBlocked = false;
            if (manual && SafeMode) { SafeMode = false; return new() { new HandoffAction.SafeMode(false) }; }
            return new();
        }
        Failures += 1;
        var output = new List<HandoffAction> { new HandoffAction.Info($"Claim failed ({Failures} in a row): {failure ?? "the earbuds did not connect"}") };
        if (Failures >= Settings.SafeModeAfter && !manual && !SafeMode)
        {
            SafeMode = true;
            safeSince = now;
            output.Add(new HandoffAction.SafeMode(true));
        }
        return output;
    }

    public void ReleaseFinished(DateTime now)
    {
        if (Pending == PendingKind.Release) Pending = null;
        lastHandoff = now;
        lastReleaseAt = now;
    }

    public void ClearSafeMode() { SafeMode = false; Failures = 0; }

    /// <summary>What this device tells the others: right after it let go or lost the earbuds, never "playing".</summary>
    public bool AdvertisedPlaying(bool playing) => playing && !ClaimBlocked;

    public List<HandoffAction> Tick(HandoffInput i)
    {
        var now = i.Now;
        var output = new List<HandoffAction>();
        try { return TickBody(i, now, output); }
        finally
        {
            prevOwner = i.Owner;
            Phase = ComputePhase(i);
        }
    }

    private List<HandoffAction> TickBody(HandoffInput i, DateTime now, List<HandoffAction> output)
    {
        bool playedJustNow = i.Playing || Sec(now, lastLocalPlayingAt) < 1.5;
        if (ClaimBlocked && !i.Playing && Sec(now, lastLocalPlayingAt) >= 2 && Sec(now, blockedAt) >= 4) ClaimBlocked = false;
        if (i.Playing) { lastLocalPlayingAt = now; playingSince ??= now; } else playingSince = null;
        if (i.Ear.Ear == Ear.None) earOutSince ??= now; else earOutSince = null;
        if (i.Owner) ownerSince ??= now; else ownerSince = null;
        var fresh = i.Peers.Where(p => Sec(now, p.SeenAt) < 5).ToList();
        var ownersNow = fresh.Where(p => p.Owner).Select(p => p.Name).ToHashSet();
        if (peerOwners.Except(ownersNow).Any(n => fresh.Any(p => p.Name == n))) peerLetGoAt = now;
        peerOwners = ownersNow;
        if (i.Owner && !prevOwner)
        {
            bool asked = Pending == PendingKind.Claim || Sec(now, claimStartedAt) < 30;
            bool handoffJustNow = Sec(now, peerLetGoAt) < 15 || Sec(now, lastReleaseAt) < 15;
            strayOwner = !asked && handoffJustNow;
        }
        else if (!i.Owner) strayOwner = false;
        if (SafeMode && (Sec(now, safeSince) >= Settings.SafeModeSeconds || i.Owner || fresh.Any(p => p.Owner)))
        {
            SafeMode = false;
            Failures = 0;
            output.Add(new HandoffAction.SafeMode(false));
        }
        bool cooled = Sec(now, lastHandoff) >= CooldownNow;
        inCallPhase = false;

        // A call anywhere gets the earbuds. The device with the call has priority.
        var cp = fresh.FirstOrDefault(p => p.Call);
        if (cp != null && !i.InCall)
        {
            inCallPhase = true;
            Ears.Clear();
            HoldReason = $"call on {cp.Name}";
            if (i.Owner && yieldedForCall != cp.Name && Pending == null)
            {
                yieldedForCall = cp.Name;
                if (i.Playing) output.AddRange(PauseBeforeRelease($"call on {cp.Name}", now));
                output.AddRange(StartRelease($"call on {cp.Name}", now));
            }
            return output;
        }
        yieldedForCall = null;
        if (i.InCall)
        {
            inCallPhase = true;
            Ears.Clear();
            HoldReason = null;
            if (!i.Owner && (i.Playing || !Settings.CallNeedsPlaying) && cooled && Pending == null && !SafeMode && !ClaimBlocked
                && i.Gate == null && EarIn(i.Ear))
                output.AddRange(StartClaim("call here", false, now));
            return output;
        }

        switch (Ears.Update(now, i.Ear, i.Playing, i.Owner, Settings.PauseWhenOneOut, Settings.ResumeWithin))
        {
            case EarPolicy.Act.Pause: output.Add(DoPause($"earbud removed ({i.Ear.Summary})", now)); break;
            case EarPolicy.Act.RemovedAlreadyStopped: output.Add(new HandoffAction.Info("earbud removed, playback already stopped, will resume when back")); break;
            case EarPolicy.Act.Resume: output.Add(new HandoffAction.Resume($"earbuds back in ({i.Ear.Summary})")); break;
            case EarPolicy.Act.AlreadyPlaying: output.Add(new HandoffAction.Info("earbuds back in, audio is already playing")); break;
            case EarPolicy.Act.Expired: output.Add(new HandoffAction.Info("resume window expired")); break;
        }

        // Lost the earbuds without asking for it.
        if (prevOwner && !i.Owner)
        {
            lostAt = now;
            bool ours = Pending == PendingKind.Release || Sec(now, lastReleaseAt) < 15;
            bool pausedRecently = Sec(now, lastPauseAt) < 6;
            if (playedJustNow && !ours && !pausedRecently) output.Add(DoPause("lost the earbuds", now));
            else if (playedJustNow) output.Add(new HandoffAction.Info("earbuds left, audio was already paused by EarHop, no second key"));
            Block(now);
        }

        var ownerBusy = fresh.FirstOrDefault(p =>
        {
            if (!p.Owner) return false;
            if (Settings.FollowAttention && i.Attention && !p.Att) return false;
            if (p.Playing)
            {
                if (p.PlayingSince is not DateTime theirs || playingSince is not DateTime mine) return true;
                return !(mine > theirs.AddSeconds(1));
            }
            if (p.LastPlayingAt is DateTime l && Sec(now, l) < Settings.Quiet) return true;
            return false;
        });
        var outranker = fresh.FirstOrDefault(p =>
        {
            if (p.Owner || !p.Playing || !(p.Ear == Ear.Both || p.Ear == Ear.One) || Prio(p.Name) >= Prio(Settings.DeviceName)) return false;
            if (p.PlayingSince is not DateTime theirs || playingSince is not DateTime mine) return false;
            return Math.Abs(Sec(theirs, mine)) <= 2.5;
        });
        double relNeed = Settings.MinPlay;
        bool playedLongEnough = playingSince is DateTime ps && Sec(now, ps) >= Settings.MinPlay;

        HoldReason = null;
        if (!i.Owner && i.Playing && Pending == null)
        {
            if (SafeMode) HoldReason = "safe mode after repeated failures";
            else if (ClaimBlocked) HoldReason = "just let go, waiting for this device's audio to stop";
            else if (ownerBusy != null) HoldReason = $"{ownerBusy.Name} is still playing";
            else if (outranker != null) HoldReason = $"{outranker.Name} has priority";
            else if (!EarIn(i.Ear)) HoldReason = "earbuds are not in your ears";
            else if (i.Gate != null) HoldReason = $"held: {i.Gate}";
            else if (!cooled) HoldReason = "cooling down after the last switch";
            else if (!playedLongEnough) HoldReason = "waiting for playback to settle";
        }

        if (!i.Owner && i.Playing && cooled && Pending == null && !SafeMode && !ClaimBlocked && ownerBusy == null && outranker == null
            && EarIn(i.Ear) && playedLongEnough)
        {
            if (i.Gate != null)
            {
                if (Sec(now, gateLoggedAt) > 60) { gateLoggedAt = now; output.Add(new HandoffAction.Info($"auto claim held: {i.Gate}")); }
            }
            else output.AddRange(StartClaim("playing here", false, now));
            return output;
        }
        if (i.Owner && cooled && Pending == null && earOutSince is DateTime eo && Sec(now, eo) >= Settings.ReleaseAfterOut
            && (!i.OnSpeaker || Sec(now, lastLocalPlayingAt) >= Settings.ReleaseAfterOut))
        {
            output.AddRange(StartRelease("out of ears", now));
            return output;
        }
        if (i.Owner && cooled && Pending == null && i.Playing && playingSince is DateTime mine2)
        {
            var taker = fresh.FirstOrDefault(p => !p.Owner && p.Playing && p.Ear != Ear.None
                && p.PlayingSince is DateTime s && Sec(now, s) >= relNeed
                && ((s > mine2.AddSeconds(1) && s > (ownerSince ?? DateTime.MinValue))
                    || (Settings.FollowAttention && p.Att && !i.Attention)));
            if (taker != null)
            {
                output.AddRange(PauseBeforeRelease($"{taker.Name} started playing", now));
                output.AddRange(StartRelease($"{taker.Name} started playing", now));
                return output;
            }
        }
        if (i.Owner && strayOwner && Pending == null && !i.Playing && ownerSince is DateTime os && Sec(now, os) < 20)
        {
            var taker = fresh.FirstOrDefault(p => !p.Owner && p.Playing && p.Ear != Ear.None
                && p.PlayingSince is DateTime s && Sec(now, s) >= relNeed);
            if (taker != null)
            {
                strayOwner = false;
                output.AddRange(StartRelease($"the earbuds came here by themselves while {taker.Name} was taking them", now));
                return output;
            }
        }
        if (i.Owner && cooled && Pending == null && !i.Playing && Sec(now, lastLocalPlayingAt) >= Settings.Quiet)
        {
            var taker = fresh.FirstOrDefault(p => !p.Owner && p.Playing && p.Ear != Ear.None
                && p.PlayingSince is DateTime since && Sec(now, since) >= relNeed && since > (ownerSince ?? DateTime.MinValue));
            if (taker != null) output.AddRange(StartRelease($"{taker.Name} is playing", now));
        }
        return output;
    }

    private HandoffPhase ComputePhase(HandoffInput i)
    {
        if (Pending == PendingKind.Claim) return HandoffPhase.ClaimPending;
        if (Pending == PendingKind.Release) return HandoffPhase.ReleasePending;
        if (inCallPhase) return HandoffPhase.CallPriority;
        if (i.Owner)
        {
            if (Ears.Waiting) return HandoffPhase.OwnerPausedByEarRemoval;
            return i.Playing ? HandoffPhase.OwnerPlaying : HandoffPhase.OwnerIdle;
        }
        if (SafeMode) return HandoffPhase.SafeMode;
        if (Sec(i.Now, lostAt) < 10) return HandoffPhase.LostOwnership;
        return i.Playing ? HandoffPhase.PlayingWithoutEarbuds : HandoffPhase.Idle;
    }

    public string Describe(HandoffInput i, string profile)
    {
        var peer = string.Join(",", i.Peers.Where(p => Sec(i.Now, p.SeenAt) < 5).Select(p =>
        {
            string since = p.PlayingSince is DateTime s ? $"{Sec(i.Now, s):0.0}s" : "-";
            return $"{p.Name}(owner={(p.Owner ? 1 : 0)} playing={(p.Playing ? 1 : 0)} since={since})";
        }));
        return $"dev={Settings.DeviceName} profile={profile} playing={(i.Playing ? 1 : 0)} owner={(i.Owner ? 1 : 0)}"
             + $" ear={i.Ear.Summary} call={(i.InCall ? 1 : 0)} peers=[{peer}]";
    }
}
