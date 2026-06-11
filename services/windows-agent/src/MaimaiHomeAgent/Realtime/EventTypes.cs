namespace MaimaiHomeAgent.Realtime;

/// <summary>
/// Canonical event type strings broadcast over the /api/events WebSocket.
/// Using constants keeps producers (audio/file watchers) and consumers (mobile/PC)
/// in lockstep without magic strings.
/// </summary>
public static class EventTypes
{
    public const string AudioState = "audio.state";
    public const string AudioDeviceChanged = "audio.device.changed";
    public const string FileCreated = "file.created";
    public const string FileDeleted = "file.deleted";
    public const string FileRenamed = "file.renamed";
    public const string FileMoved = "file.moved";
    public const string DeviceUnavailable = "device.unavailable";
    public const string PowerShutdownExecuting = "power.shutdown.executing";
    public const string PowerShutdownFailed = "power.shutdown.failed";
    public const string SettingsUpdated = "settings.updated";
    public const string LauncherShown = "launcher.shown";
    public const string LauncherMinimized = "launcher.minimized";
    public const string LauncherItemStarted = "launcher.item.started";
    public const string LauncherItemFailed = "launcher.item.failed";
    public const string LauncherItemStopStarted = "launcher.item.stop.started";
    public const string LauncherItemStopCompleted = "launcher.item.stop.completed";
    public const string LauncherItemStopFailed = "launcher.item.stop.failed";
}
