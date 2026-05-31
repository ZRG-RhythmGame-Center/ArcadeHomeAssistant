using System.Runtime.InteropServices;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using MaimaiHomeAgent.Realtime;

namespace MaimaiHomeAgent.Audio;

public sealed class DeviceChangeNotifier : IHostedService, IAudioDeviceNotificationSink
{
    private readonly IAudioDeviceNotificationSource _source;
    private readonly IAudioStaDispatcher _dispatcher;
    private readonly IAudioService _audioService;
    private readonly EventPublisher _events;
    private readonly ILogger<DeviceChangeNotifier> _logger;
    private int _registered;

    public DeviceChangeNotifier(IAudioDeviceNotificationSource source, IAudioStaDispatcher dispatcher, IAudioService audioService, EventPublisher events, ILogger<DeviceChangeNotifier> logger)
    {
        _source = source;
        _dispatcher = dispatcher;
        _audioService = audioService;
        _events = events;
        _logger = logger;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        if (Interlocked.Exchange(ref _registered, 1) == 0)
        {
            _source.Register(this);
        }

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        if (Interlocked.Exchange(ref _registered, 0) == 0)
        {
            return Task.CompletedTask;
        }

        try
        {
            _source.Unregister(this);
        }
        catch (COMException ex)
        {
            _logger.LogWarning(ex, "Failed to unregister audio device notification callback cleanly.");
        }

        return Task.CompletedTask;
    }

    public void OnDefaultDeviceChanged(string? deviceId) => _ = HandleCallbackAsync(nameof(OnDefaultDeviceChanged), deviceId);
    public void OnDeviceAdded(string deviceId) => _ = HandleCallbackAsync(nameof(OnDeviceAdded), deviceId);
    public void OnDeviceRemoved(string deviceId) => _ = HandleCallbackAsync(nameof(OnDeviceRemoved), deviceId);
    public void OnDeviceStateChanged(string deviceId) => _ = HandleCallbackAsync(nameof(OnDeviceStateChanged), deviceId);

    private async Task HandleCallbackAsync(string callbackName, string? deviceId)
    {
        try
        {
            var devices = await _dispatcher.InvokeAsync(() => _audioService.ListDevicesAsync()).ConfigureAwait(false);
            _events.PublishAudioDeviceChanged(DeviceEndpoints.Project(devices));
        }
        catch (COMException ex)
        {
            _logger.LogWarning(ex, "Audio device callback {CallbackName} failed for {DeviceId} due to COM error.", callbackName, deviceId);
        }
        catch (AudioOperationException ex) when (ex.InnerException is COMException)
        {
            _logger.LogWarning(ex, "Audio device callback {CallbackName} failed for {DeviceId} due to COM error.", callbackName, deviceId);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Audio device callback {CallbackName} failed for {DeviceId}.", callbackName, deviceId);
        }
    }
}
