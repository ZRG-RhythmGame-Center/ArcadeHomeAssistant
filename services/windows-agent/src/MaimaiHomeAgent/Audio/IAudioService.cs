namespace MaimaiHomeAgent.Audio;

/// <summary>
/// Abstraction over the Windows Core Audio default playback endpoint.
/// All implementations MUST funnel COM access through a single STA thread
/// (see <see cref="AudioStaDispatcher"/>); never call from the request thread
/// pool directly.
/// </summary>
public interface IAudioService
{
    /// <summary>
    /// Reads the current master volume, mute flag, and default device id.
    /// </summary>
    Task<AudioState> GetStateAsync();

    /// <summary>
    /// Sets the master volume on the default playback device.
    /// </summary>
    /// <param name="level">Scalar in <c>[0.0, 1.0]</c>. Values outside the
    /// range are rejected with <see cref="ArgumentOutOfRangeException"/>.</param>
    Task SetVolumeAsync(double level);

    /// <summary>
    /// Sets the mute flag on the default playback device.
    /// </summary>
    Task SetMuteAsync(bool muted);

    /// <summary>
    /// Enumerates known playback endpoints (active, disabled, unplugged, not present).
    /// </summary>
    Task<IReadOnlyList<AudioDevice>> ListDevicesAsync();

    /// <summary>
    /// Promotes the device identified by <paramref name="deviceId"/> to the
    /// default playback role.
    /// </summary>
    Task SetDefaultDeviceAsync(Guid deviceId);
}
