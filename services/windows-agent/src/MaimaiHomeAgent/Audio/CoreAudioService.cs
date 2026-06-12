using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using NAudio.CoreAudioApi;
using NAudioDeviceState = NAudio.CoreAudioApi.DeviceState;

namespace MaimaiHomeAgent.Audio;

/// <summary>
///     Concrete <see cref="IAudioService" /> implementation backed by NAudio's
///     CoreAudioApi wrapper. NAudio exposes the same Windows Core Audio endpoint
///     interfaces without the AudioSwitcher controller/session layer that was
///     causing disposal and callback contention.
/// </summary>
public sealed class CoreAudioService : IAudioService, IAudioDeviceCacheInvalidator, IDisposable
{
    private const NAudioDeviceState ListedDeviceStates =
        NAudioDeviceState.Active | NAudioDeviceState.Disabled | NAudioDeviceState.NotPresent |
        NAudioDeviceState.Unplugged;

    private static readonly TimeSpan DeviceCacheTtl = TimeSpan.FromSeconds(30);

    private readonly object _deviceCacheGate = new();
    private readonly IAudioStaDispatcher _dispatcher;
    private readonly ILogger<CoreAudioService> _logger;
    private IReadOnlyList<AudioDevice>? _deviceCache;
    private DateTimeOffset _deviceCacheExpiresAt;
    private int _disposed;

    public CoreAudioService(IAudioStaDispatcher dispatcher, ILogger<CoreAudioService> logger)
    {
        _dispatcher = dispatcher;
        _logger = logger;
    }

    public void InvalidateDeviceCache()
    {
        lock (_deviceCacheGate)
        {
            _deviceCache = null;
            _deviceCacheExpiresAt = default;
        }
    }

    public bool TryUpdateDefaultDeviceCache(string? endpointId, out IReadOnlyList<AudioDevice> devices)
    {
        devices = Array.Empty<AudioDevice>();
        if (string.IsNullOrWhiteSpace(endpointId)) return false;

        var defaultDeviceId = AudioDeviceId.FromEndpointId(endpointId);
        lock (_deviceCacheGate)
        {
            if (_deviceCache is null || DateTimeOffset.UtcNow >= _deviceCacheExpiresAt) return false;

            devices = _deviceCache
                .Select(device => device with { IsDefault = device.Id == defaultDeviceId })
                .ToArray();
            _deviceCache = devices;
            _deviceCacheExpiresAt = DateTimeOffset.UtcNow + DeviceCacheTtl;
            return true;
        }
    }

    public Task<AudioState> GetStateAsync()
    {
        return _dispatcher.InvokeAsync(() => Task.FromResult(WithAudio(() =>
        {
            using var enumerator = new MMDeviceEnumerator();
            using var device = GetDefaultPlaybackDeviceOrNull(enumerator);
            return new AudioState(
                device is null ? 0d : Math.Clamp(device.AudioEndpointVolume.MasterVolumeLevelScalar, 0f, 1f),
                device?.AudioEndpointVolume.Mute ?? false,
                device is null ? null : AudioDeviceId.FromEndpointId(device.ID));
        }, nameof(GetStateAsync))));
    }

    public Task SetVolumeAsync(double level)
    {
        if (double.IsNaN(level) || level < 0d || level > 1d)
            throw new ArgumentOutOfRangeException(nameof(level), level,
                "level must be in [0.0, 1.0].");

        return _dispatcher.InvokeAsync(() =>
        {
            WithAudio(() =>
            {
                using var enumerator = new MMDeviceEnumerator();
                using var device = GetDefaultPlaybackDeviceOrThrow(enumerator);
                device.AudioEndpointVolume.MasterVolumeLevelScalar = (float)level;
            }, nameof(SetVolumeAsync));
            return Task.CompletedTask;
        });
    }

    public Task SetMuteAsync(bool muted)
    {
        return _dispatcher.InvokeAsync(() =>
        {
            WithAudio(() =>
            {
                using var enumerator = new MMDeviceEnumerator();
                using var device = GetDefaultPlaybackDeviceOrThrow(enumerator);
                device.AudioEndpointVolume.Mute = muted;
            }, nameof(SetMuteAsync));
            return Task.CompletedTask;
        });
    }

    public Task<IReadOnlyList<AudioDevice>> ListDevicesAsync()
    {
        lock (_deviceCacheGate)
        {
            if (_deviceCache is not null && DateTimeOffset.UtcNow < _deviceCacheExpiresAt)
                return Task.FromResult(_deviceCache);
        }

        return _dispatcher.InvokeAsync(() => Task.FromResult(WithAudio(() =>
        {
            using var enumerator = new MMDeviceEnumerator();
            using var defaultDevice = GetDefaultPlaybackDeviceOrNull(enumerator);
            var defaultEndpointId = defaultDevice?.ID;
            var devices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, ListedDeviceStates);

            var result = new List<AudioDevice>();
            foreach (var device in devices)
                using (device)
                {
                    result.Add(new AudioDevice(
                        AudioDeviceId.FromEndpointId(device.ID),
                        device.FriendlyName,
                        string.Equals(device.ID, defaultEndpointId, StringComparison.OrdinalIgnoreCase),
                        MapState(device.State)));
                }

            IReadOnlyList<AudioDevice> snapshot = result.ToArray();
            lock (_deviceCacheGate)
            {
                _deviceCache = snapshot;
                _deviceCacheExpiresAt = DateTimeOffset.UtcNow + DeviceCacheTtl;
            }

            return snapshot;
        }, nameof(ListDevicesAsync))));
    }

    public Task SetDefaultDeviceAsync(Guid deviceId)
    {
        return _dispatcher.InvokeAsync(() =>
        {
            WithAudio(() =>
            {
                using var enumerator = new MMDeviceEnumerator();
                var target = FindActiveDeviceEndpointId(enumerator, deviceId) ??
                             throw new AudioDeviceNotFoundException(deviceId);
                using var policy = new PolicyConfigClient();
                policy.SetDefaultEndpoint(target, PolicyRole.Console);
                policy.SetDefaultEndpoint(target, PolicyRole.Multimedia);
                policy.SetDefaultEndpoint(target, PolicyRole.Communications);

                using var defaultDevice = GetDefaultPlaybackDeviceOrNull(enumerator);
                if (defaultDevice is null ||
                    !string.Equals(defaultDevice.ID, target, StringComparison.OrdinalIgnoreCase))
                    throw new AudioOperationException(
                        $"Windows did not promote audio device {deviceId} to the default playback device.");

                UpdateDefaultDeviceCache(deviceId);
            }, nameof(SetDefaultDeviceAsync));
            return Task.CompletedTask;
        });
    }

    public void Dispose()
    {
        Interlocked.Exchange(ref _disposed, 1);
    }

    private void UpdateDefaultDeviceCache(Guid defaultDeviceId)
    {
        lock (_deviceCacheGate)
        {
            if (_deviceCache is null) return;

            _deviceCache = _deviceCache
                .Select(device => device with { IsDefault = device.Id == defaultDeviceId })
                .ToArray();
            _deviceCacheExpiresAt = DateTimeOffset.UtcNow + DeviceCacheTtl;
        }
    }

    private T WithAudio<T>(Func<T> action, string operation)
    {
        ObjectDisposedException.ThrowIf(_disposed != 0, this);
        try
        {
            return action();
        }
        catch (COMException ex)
        {
            throw HandleCom(ex, operation);
        }
    }

    private void WithAudio(Action action, string operation)
    {
        WithAudio(() =>
        {
            action();
            return true;
        }, operation);
    }

    private static MMDevice? GetDefaultPlaybackDeviceOrNull(MMDeviceEnumerator enumerator)
    {
        try
        {
            return enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        }
        catch (COMException ex) when ((uint)ex.HResult == 0x80070490)
        {
            return null;
        }
    }

    private static MMDevice GetDefaultPlaybackDeviceOrThrow(MMDeviceEnumerator enumerator)
    {
        return GetDefaultPlaybackDeviceOrNull(enumerator)
               ?? throw new AudioOperationException("No default playback device is available.");
    }

    private static string? FindActiveDeviceEndpointId(MMDeviceEnumerator enumerator, Guid deviceId)
    {
        var devices = enumerator.EnumerateAudioEndPoints(DataFlow.Render, NAudioDeviceState.Active);

        foreach (var device in devices)
            using (device)
            {
                if (AudioDeviceId.FromEndpointId(device.ID) == deviceId) return device.ID;
            }

        return null;
    }

    private AudioOperationException HandleCom(COMException ex, string operation)
    {
        _logger.LogError(ex, "Core Audio {Operation} failed (HResult=0x{HResult:X8}).",
            operation, (uint)ex.HResult);
        return new AudioOperationException(
            $"Audio operation '{operation}' failed: {ex.Message}", ex);
    }

    private static DeviceState MapState(NAudioDeviceState state)
    {
        return state switch
        {
            NAudioDeviceState.Active => DeviceState.Active,
            NAudioDeviceState.Disabled => DeviceState.Disabled,
            NAudioDeviceState.NotPresent => DeviceState.NotPresent,
            NAudioDeviceState.Unplugged => DeviceState.Unplugged,
            _ => DeviceState.NotPresent
        };
    }
}

internal static class AudioDeviceId
{
    public static Guid FromEndpointId(string endpointId)
    {
        var start = endpointId.LastIndexOf('{');
        var end = endpointId.LastIndexOf('}');
        if (start >= 0 && end > start &&
            Guid.TryParse(endpointId.Substring(start + 1, end - start - 1), out var parsed)) return parsed;

        return GuidUtility.Create(GuidUtility.UrlNamespace, endpointId);
    }
}

internal sealed class PolicyConfigClient : IDisposable
{
    private readonly object _comObject;
    private readonly IPolicyConfig _policyConfig;

    public PolicyConfigClient()
    {
        var type = Type.GetTypeFromCLSID(new Guid("870AF99C-171D-4F9E-AF0D-E63DF40C2BC9"), true)!;
        _comObject = Activator.CreateInstance(type) ??
                     throw new InvalidOperationException("Could not create PolicyConfigClient COM object.");
        _policyConfig = (IPolicyConfig)_comObject;
    }

    public void Dispose()
    {
        if (Marshal.IsComObject(_comObject)) Marshal.FinalReleaseComObject(_comObject);
    }

    public void SetDefaultEndpoint(string endpointId, PolicyRole role)
    {
        Marshal.ThrowExceptionForHR(_policyConfig.SetDefaultEndpoint(endpointId, role));
    }
}

internal enum PolicyRole
{
    Console = 0,
    Multimedia = 1,
    Communications = 2
}

[ComImport]
[Guid("F8679F50-850A-41CF-9C72-430F290290C8")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IPolicyConfig
{
    [PreserveSig]
    int GetMixFormat();

    [PreserveSig]
    int GetDeviceFormat();

    [PreserveSig]
    int ResetDeviceFormat();

    [PreserveSig]
    int SetDeviceFormat();

    [PreserveSig]
    int GetProcessingPeriod();

    [PreserveSig]
    int SetProcessingPeriod();

    [PreserveSig]
    int GetShareMode();

    [PreserveSig]
    int SetShareMode();

    [PreserveSig]
    int GetPropertyValue();

    [PreserveSig]
    int SetPropertyValue();

    [PreserveSig]
    int SetDefaultEndpoint([MarshalAs(UnmanagedType.LPWStr)] string endpointId, PolicyRole role);

    [PreserveSig]
    int SetEndpointVisibility();
}

internal static class GuidUtility
{
    public static readonly Guid UrlNamespace = new("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    public static Guid Create(Guid namespaceId, string name)
    {
        var namespaceBytes = namespaceId.ToByteArray();
        SwapByteOrder(namespaceBytes);

        var nameBytes = Encoding.UTF8.GetBytes(name);
        var data = namespaceBytes.Concat(nameBytes).ToArray();
        var hash = SHA1.HashData(data);

        var newGuid = new byte[16];
        Array.Copy(hash, 0, newGuid, 0, 16);

        newGuid[6] = (byte)((newGuid[6] & 0x0F) | 0x50);
        newGuid[8] = (byte)((newGuid[8] & 0x3F) | 0x80);

        SwapByteOrder(newGuid);
        return new Guid(newGuid);
    }

    private static void SwapByteOrder(byte[] guid)
    {
        (guid[0], guid[3]) = (guid[3], guid[0]);
        (guid[1], guid[2]) = (guid[2], guid[1]);
        (guid[4], guid[5]) = (guid[5], guid[4]);
        (guid[6], guid[7]) = (guid[7], guid[6]);
    }
}