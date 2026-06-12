namespace MaimaiHomeAgent.Audio;

/// <summary>
///     Lifecycle state of an audio device, mirroring the relevant subset of
///     Windows Core Audio <c>DEVICE_STATE_XXX</c> flags.
/// </summary>
public enum DeviceState
{
    Active,
    Disabled,
    NotPresent,
    Unplugged
}

/// <summary>
///     Snapshot of the default playback endpoint. <see cref="MasterVolume" /> is the
///     scalar volume in <c>[0.0, 1.0]</c>. <see cref="DefaultDeviceId" /> is null
///     when no default playback device is reported by the OS.
/// </summary>
public sealed record AudioState(
    double MasterVolume,
    bool Muted,
    Guid? DefaultDeviceId);

/// <summary>
///     Lightweight projection of a Core Audio playback endpoint.
/// </summary>
public sealed record AudioDevice(
    Guid Id,
    string Name,
    bool IsDefault,
    DeviceState State);