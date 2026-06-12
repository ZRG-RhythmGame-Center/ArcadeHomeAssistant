using System.Text.Json;
using MaimaiHomeAgent.Audio;

namespace MaimaiHomeAgent.Realtime;

/// <summary>
///     Domain-semantic facade over <see cref="EventHub" />. Producers (audio
///     endpoints, device watchers, file watchers) call the typed publish methods
///     here instead of crafting <see cref="EventEnvelope" /> values themselves —
///     this centralizes the JSON serialization options, the timestamp source, and
///     the canonical event type strings.
/// </summary>
/// <remarks>
///     All publish methods are <b>fire-and-forget</b>: they assign
///     <c>_ = hub.BroadcastAsync(...)</c> and return synchronously so HTTP
///     handlers and COM callbacks never block on WebSocket I/O. Failures inside
///     the hub are already swallowed per-session, so the discarded task can never
///     fault into an unobserved-exception unless someone changes EventHub itself.
/// </remarks>
public sealed class EventPublisher
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    private readonly EventHub _hub;

    public EventPublisher(EventHub hub)
    {
        _hub = hub ?? throw new ArgumentNullException(nameof(hub));
    }

    /// <summary>
    ///     Broadcasts an <see cref="EventTypes.AudioState" /> envelope after a
    ///     volume / mute / default-device change.
    /// </summary>
    public void PublishAudioStateChanged(AudioStateDto state)
    {
        ArgumentNullException.ThrowIfNull(state);
        Broadcast(EventTypes.AudioState, state);
    }

    /// <summary>
    ///     Broadcasts an <see cref="EventTypes.AudioDeviceChanged" /> envelope when
    ///     the set of audio devices (or the default selection) changes.
    /// </summary>
    public void PublishAudioDeviceChanged(IReadOnlyList<DeviceResponse> devices)
    {
        ArgumentNullException.ThrowIfNull(devices);
        Broadcast(EventTypes.AudioDeviceChanged, devices);
    }

    /// <summary>
    ///     Broadcasts a file-system change envelope. The caller picks the type
    ///     (one of <see cref="EventTypes.FileCreated" />, <see cref="EventTypes.FileDeleted" />,
    ///     <see cref="EventTypes.FileRenamed" />, <see cref="EventTypes.FileMoved" />).
    /// </summary>
    public void PublishFileEvent(string eventType, FileEventDto payload)
    {
        ArgumentException.ThrowIfNullOrEmpty(eventType);
        ArgumentNullException.ThrowIfNull(payload);
        Broadcast(eventType, payload);
    }

    /// <summary>
    ///     Broadcasts an <see cref="EventTypes.DeviceUnavailable" /> envelope when
    ///     the agent observes that a previously-known audio device is no longer
    ///     reachable. Payload is <c>{ deviceId }</c>.
    /// </summary>
    public void PublishDeviceUnavailable(string deviceId)
    {
        ArgumentException.ThrowIfNullOrEmpty(deviceId);
        Broadcast(EventTypes.DeviceUnavailable, new { deviceId });
    }

    public void PublishRemoteShutdownEvent<TPayload>(string eventType, TPayload payload)
    {
        ArgumentException.ThrowIfNullOrEmpty(eventType);
        ArgumentNullException.ThrowIfNull(payload);
        Broadcast(eventType, payload);
    }

    public void PublishSettingsUpdated<TPayload>(TPayload payload)
    {
        ArgumentNullException.ThrowIfNull(payload);
        Broadcast(EventTypes.SettingsUpdated, payload);
    }

    public void PublishLauncherEvent<TPayload>(string eventType, TPayload payload)
    {
        ArgumentException.ThrowIfNullOrEmpty(eventType);
        ArgumentNullException.ThrowIfNull(payload);
        Broadcast(eventType, payload);
    }

    private void Broadcast<T>(string eventType, T payload)
    {
        var element = JsonSerializer.SerializeToElement(payload, JsonOptions);
        var envelope = new EventEnvelope(eventType, element, DateTimeOffset.UtcNow);
        // Fire-and-forget on purpose: callers (HTTP handlers, STA callbacks)
        // must not block on the WebSocket fan-out.
        _ = _hub.BroadcastAsync(envelope);
    }
}