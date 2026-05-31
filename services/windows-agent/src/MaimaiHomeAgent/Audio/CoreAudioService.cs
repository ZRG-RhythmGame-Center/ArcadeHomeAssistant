using System.Runtime.InteropServices;
using AudioSwitcher.AudioApi;
using AudioSwitcher.AudioApi.CoreAudio;
using Microsoft.Extensions.Logging;
using AsDeviceState = AudioSwitcher.AudioApi.DeviceState;
using DomainDeviceState = MaimaiHomeAgent.Audio.DeviceState;

namespace MaimaiHomeAgent.Audio;

/// <summary>
/// Concrete <see cref="IAudioService"/> implementation backed by
/// AudioSwitcher.AudioApi.CoreAudio. Every public method funnels its COM
/// access through <see cref="AudioStaDispatcher"/>; nothing here may run on
/// the request thread pool directly.
/// </summary>
public sealed class CoreAudioService : IAudioService, IDisposable
{
    private readonly AudioStaDispatcher _dispatcher;
    private readonly ILogger<CoreAudioService> _logger;
    private CoreAudioController? _controller;
    private int _disposed;

    public CoreAudioService(AudioStaDispatcher dispatcher, ILogger<CoreAudioService> logger)
    {
        _dispatcher = dispatcher;
        _logger = logger;
    }

    public Task<AudioState> GetStateAsync()
    {
        return _dispatcher.InvokeAsync(() =>
        {
            try
            {
                var controller = GetController();
                var device = controller.DefaultPlaybackDevice;
                var state = new AudioState(
                    MasterVolume: device is null ? 0d : Math.Clamp(device.Volume / 100d, 0d, 1d),
                    Muted: device?.IsMuted ?? false,
                    DefaultDeviceId: device?.Id);
                return Task.FromResult(state);
            }
            catch (COMException ex)
            {
                throw HandleCom(ex, "GetState");
            }
        });
    }

    public Task SetVolumeAsync(double level)
    {
        if (double.IsNaN(level) || level < 0d || level > 1d)
        {
            throw new ArgumentOutOfRangeException(nameof(level), level,
                "level must be in [0.0, 1.0].");
        }

        return _dispatcher.InvokeAsync(() =>
        {
            try
            {
                var controller = GetController();
                var device = controller.DefaultPlaybackDevice
                    ?? throw new AudioOperationException("No default playback device is available.");
                // CoreAudioDevice.Volume is a synchronous setter expecting a 0-100 scalar.
                device.Volume = level * 100d;
            }
            catch (COMException ex)
            {
                throw HandleCom(ex, "SetVolume");
            }
            return Task.CompletedTask;
        });
    }

    public Task SetMuteAsync(bool muted)
    {
        return _dispatcher.InvokeAsync(async () =>
        {
            try
            {
                var controller = GetController();
                var device = controller.DefaultPlaybackDevice
                    ?? throw new AudioOperationException("No default playback device is available.");
                await device.MuteAsync(muted).ConfigureAwait(false);
            }
            catch (COMException ex)
            {
                throw HandleCom(ex, "SetMute");
            }
        });
    }

    public Task<IReadOnlyList<AudioDevice>> ListDevicesAsync()
    {
        return _dispatcher.InvokeAsync(() =>
        {
            try
            {
                var controller = GetController();
                var allStates = AsDeviceState.Active
                                | AsDeviceState.Disabled
                                | AsDeviceState.NotPresent
                                | AsDeviceState.Unplugged;
                var devices = controller.GetPlaybackDevices(allStates);
                var defaultId = controller.DefaultPlaybackDevice?.Id;

                var result = new List<AudioDevice>();
                foreach (var d in devices)
                {
                    result.Add(new AudioDevice(
                        Id: d.Id,
                        Name: d.FullName ?? d.Name ?? d.Id.ToString(),
                        IsDefault: defaultId.HasValue && defaultId.Value == d.Id,
                        State: MapState(d.State)));
                }

                return Task.FromResult<IReadOnlyList<AudioDevice>>(result);
            }
            catch (COMException ex)
            {
                throw HandleCom(ex, "ListDevices");
            }
        });
    }

    public Task SetDefaultDeviceAsync(Guid deviceId)
    {
        return _dispatcher.InvokeAsync(async () =>
        {
            try
            {
                var controller = GetController();
                var device = controller.GetDevice(deviceId)
                    ?? throw new AudioDeviceNotFoundException(deviceId);

                await device.SetAsDefaultAsync().ConfigureAwait(false);
                await device.SetAsDefaultCommunicationsAsync().ConfigureAwait(false);
            }
            catch (COMException ex)
            {
                throw HandleCom(ex, "SetDefaultDevice");
            }
        });
    }

    private CoreAudioController GetController()
    {
        // Lazily created on the STA thread to keep all COM activation pinned
        // to that apartment.
        return _controller ??= new CoreAudioController();
    }

    private AudioOperationException HandleCom(COMException ex, string operation)
    {
        _logger.LogError(ex, "Core Audio {Operation} failed (HResult=0x{HResult:X8}).",
            operation, (uint)ex.HResult);
        return new AudioOperationException(
            $"Audio operation '{operation}' failed: {ex.Message}", ex);
    }

    private static DomainDeviceState MapState(AsDeviceState state) => state switch
    {
        AsDeviceState.Active => DomainDeviceState.Active,
        AsDeviceState.Disabled => DomainDeviceState.Disabled,
        AsDeviceState.NotPresent => DomainDeviceState.NotPresent,
        AsDeviceState.Unplugged => DomainDeviceState.Unplugged,
        _ => DomainDeviceState.NotPresent,
    };

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        // The controller was created on the STA thread; dispose it from the
        // same thread to avoid cross-apartment finalization.
        var controller = _controller;
        _controller = null;
        if (controller is null)
        {
            return;
        }

        try
        {
            _dispatcher.InvokeAsync(() =>
            {
                controller.Dispose();
                return Task.CompletedTask;
            }).GetAwaiter().GetResult();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to dispose CoreAudioController cleanly.");
        }
    }
}
