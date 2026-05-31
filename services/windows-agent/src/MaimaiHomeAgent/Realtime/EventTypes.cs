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
}
