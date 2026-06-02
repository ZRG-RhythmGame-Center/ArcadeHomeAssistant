using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;
using NAudioDeviceState = NAudio.CoreAudioApi.DeviceState;

namespace MaimaiHomeAgent.Audio;

public sealed partial class NAudioDeviceNotificationSource : IAudioDeviceNotificationSource, IDisposable
{
    private readonly MMDeviceEnumerator _enumerator = new();
    private NotificationClient? _client;
    private int _registered;
    private int _disposed;

    public void Register(IAudioDeviceNotificationSink sink)
    {
        ArgumentNullException.ThrowIfNull(sink);
        ObjectDisposedException.ThrowIf(_disposed != 0, this);

        if (Interlocked.Exchange(ref _registered, 1) != 0)
        {
            throw new InvalidOperationException("Audio device notifications are already registered.");
        }

        var client = new NotificationClient(sink);
        try
        {
            _enumerator.RegisterEndpointNotificationCallback(client);
            _client = client;
        }
        catch
        {
            _client = null;
            Interlocked.Exchange(ref _registered, 0);
            throw;
        }
    }

    public void Unregister(IAudioDeviceNotificationSink sink)
    {
        ArgumentNullException.ThrowIfNull(sink);

        if (Interlocked.Exchange(ref _registered, 0) == 0)
        {
            return;
        }

        var client = _client;
        _client = null;
        if (client is not null)
        {
            _enumerator.UnregisterEndpointNotificationCallback(client);
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return;
        }

        Interlocked.Exchange(ref _registered, 0);
        var client = _client;
        _client = null;
        try
        {
            if (client is not null)
            {
                _enumerator.UnregisterEndpointNotificationCallback(client);
            }
        }
        catch
        {
        }

        _enumerator.Dispose();
    }

    private sealed partial class NotificationClient(IAudioDeviceNotificationSink sink) : IMMNotificationClient
    {
        public void OnDeviceStateChanged(string deviceId, NAudioDeviceState newState) => sink.OnDeviceStateChanged(deviceId);
        public void OnDeviceAdded(string pwstrDeviceId) => sink.OnDeviceAdded(pwstrDeviceId);
        public void OnDeviceRemoved(string deviceId) => sink.OnDeviceRemoved(deviceId);

        public void OnDefaultDeviceChanged(DataFlow flow, Role role, string defaultDeviceId)
        {
            if (flow == DataFlow.Render && role == Role.Multimedia)
            {
                sink.OnDefaultDeviceChanged(defaultDeviceId);
            }
        }

        public void OnPropertyValueChanged(string pwstrDeviceId, PropertyKey key)
        {
        }
    }
}
