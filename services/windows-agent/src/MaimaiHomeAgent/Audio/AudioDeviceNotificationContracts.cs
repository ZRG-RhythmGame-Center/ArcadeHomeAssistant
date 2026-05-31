namespace MaimaiHomeAgent.Audio;

public interface IAudioDeviceNotificationSink
{
    void OnDefaultDeviceChanged(string? deviceId);
    void OnDeviceAdded(string deviceId);
    void OnDeviceRemoved(string deviceId);
    void OnDeviceStateChanged(string deviceId);
}

public interface IAudioDeviceNotificationSource
{
    void Register(IAudioDeviceNotificationSink sink);
    void Unregister(IAudioDeviceNotificationSink sink);
}
