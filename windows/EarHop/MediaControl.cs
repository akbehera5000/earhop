using Windows.Media.Control;

namespace EarHop.Win;

/// <summary>Explicit Pause and Play through Windows' media sessions (the media overlay that browsers, Spotify and
/// players publish). Never a Play/Pause toggle (AGENTS.md section 9, mistakes 1 and 13): Pause goes only to sessions
/// that report Playing, and Play only to the sessions EarHop itself paused, within 10 minutes.</summary>
static class MediaControl
{
    private static readonly List<string> pausedApps = new();
    private static DateTime pausedAt = DateTime.MinValue;

    public static async Task Pause(string why)
    {
        try
        {
            var mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var playing = mgr.GetSessions().Where(s => s.GetPlaybackInfo()?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing).ToList();
            if (playing.Count == 0) { Log.Write($"media: pause ({why}): nothing reports playing"); return; }
            lock (pausedApps) { pausedApps.Clear(); pausedAt = DateTime.UtcNow; }
            foreach (var s in playing)
            {
                bool ok = await s.TryPauseAsync();
                lock (pausedApps) if (ok) pausedApps.Add(s.SourceAppUserModelId);
                Log.Write($"media: pause {s.SourceAppUserModelId} ({why}) {(ok ? "accepted" : "refused")}");
            }
        }
        catch (Exception e) { Log.Write($"media: pause failed: {e.Message}"); }
    }

    public static async Task Resume(string why)
    {
        try
        {
            List<string> apps;
            lock (pausedApps) apps = (DateTime.UtcNow - pausedAt).TotalMinutes < 10 ? new(pausedApps) : new();
            if (apps.Count == 0) { Log.Write($"media: resume ({why}): EarHop paused nothing recently"); return; }
            var mgr = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            foreach (var s in mgr.GetSessions().Where(s => apps.Contains(s.SourceAppUserModelId)))
            {
                if (s.GetPlaybackInfo()?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing) continue;
                bool ok = await s.TryPlayAsync();
                Log.Write($"media: play {s.SourceAppUserModelId} ({why}) {(ok ? "accepted" : "refused")}");
            }
            lock (pausedApps) pausedApps.Clear();
        }
        catch (Exception e) { Log.Write($"media: resume failed: {e.Message}"); }
    }
}
