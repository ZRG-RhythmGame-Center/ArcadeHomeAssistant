using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;
using NAudioDeviceState = NAudio.CoreAudioApi.DeviceState;

namespace MaimaiHomeAgent.Audio;

public sealed partial class NAudioDeviceNotificationSource : IAudioDeviceNotificationSource, IDisposable
{
    private readonly MMDeviceEnumerator _enumerator = new();
    private NotificationClient? _client;
    private int _registered;

    public void Register(IAudioDeviceNotificationSink sink)
    {
        ArgumentNullException.ThrowIfNull(sink);
        if (Interlocked.Exchange(ref _registered, 1) != 0)
        {
            throw new InvalidOperationException("Audio device notifications are already registered.");
        }

        _client = new NotificationClient(sink);
        _enumerator.RegisterEndpointNotificationCallback(_client);
    }

    public void Unregister(IAudioDeviceNotificationSink sink)
    {
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
        try
        {
            if (_client is not null)
            {
                _enumerator.UnregisterEndpointNotificationCallback(_client);
            }
        }
        catch
        {
        }

        _client = null;
        _enumerator.Dispose();
    }

    [System.Runtime.InteropServices.Marshalling.GeneratedComClass]
    private sealed partial class NotificationClient(IAudioDeviceNotificationSink sink) : IMMNotificationClient
    {
        public void OnDeviceStateChanged(string deviceId, NAudioDeviceState newState) => sink.OnDeviceStateChanged(deviceId);
        public void OnDeviceAdded(string pwstrDeviceId) => sink.OnDeviceAdded(pwstrDeviceId);
        public void OnDeviceRemoved(string deviceId) => sink.OnDeviceRemoved(deviceId);

        public void OnDefaultDeviceChanged(DataFlow flow, Role role, string defaultDeviceId)
        {
            if (flow == DataFlow.Render)
            {
                sink.OnDefaultDeviceChanged(defaultDeviceId);
            }
        }

        public void OnPropertyValueChanged(string pwstrDeviceId, PropertyKey key)
        {
        }
    }
}
