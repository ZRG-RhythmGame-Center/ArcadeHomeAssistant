using System.Runtime.InteropServices;
using System.Threading.Channels;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using MaimaiHomeAgent.Realtime;

namespace MaimaiHomeAgent.Audio;

public sealed class DeviceChangeNotifier : IHostedService, IAudioDeviceNotificationSink
{
    private readonly IAudioDeviceNotificationSource _source;
    private readonly IAudioService _audioService;
    private readonly EventPublisher _events;
    private readonly ILogger<DeviceChangeNotifier> _logger;
    private readonly Channel<DeviceChangeSignal> _signals;
    private readonly CancellationTokenSource _cts = new();
    private Task? _worker;
    private int _registered;

    public DeviceChangeNotifier(IAudioDeviceNotificationSource source, IAudioService audioService, EventPublisher events, ILogger<DeviceChangeNotifier> logger)
    {
        _source = source;
        _audioService = audioService;
        _events = events;
        _logger = logger;
        _signals = Channel.CreateBounded<DeviceChangeSignal>(new BoundedChannelOptions(1)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false,
        });
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _worker ??= Task.Run(() => ProcessSignalsAsync(_cts.Token), CancellationToken.None);
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

        _signals.Writer.TryComplete();
        _cts.Cancel();
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

    public void OnDefaultDeviceChanged(string? deviceId) => Signal(nameof(OnDefaultDeviceChanged), deviceId);
    public void OnDeviceAdded(string deviceId) => Signal(nameof(OnDeviceAdded), deviceId);
    public void OnDeviceRemoved(string deviceId) => Signal(nameof(OnDeviceRemoved), deviceId);
    public void OnDeviceStateChanged(string deviceId) => Signal(nameof(OnDeviceStateChanged), deviceId);

    private void Signal(string callbackName, string? deviceId)
    {
        _worker ??= Task.Run(() => ProcessSignalsAsync(_cts.Token), CancellationToken.None);
        _signals.Writer.TryWrite(new DeviceChangeSignal(callbackName, deviceId));
    }

    private async Task ProcessSignalsAsync(CancellationToken ct)
    {
        try
        {
            while (await _signals.Reader.WaitToReadAsync(ct).ConfigureAwait(false))
            {
                DeviceChangeSignal signal = default;
                while (_signals.Reader.TryRead(out var next))
                {
                    signal = next;
                }

                await Task.Delay(TimeSpan.FromMilliseconds(200), ct).ConfigureAwait(false);
                while (_signals.Reader.TryRead(out var next))
                {
                    signal = next;
                }

                await HandleCallbackAsync(signal.CallbackName, signal.DeviceId).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private async Task HandleCallbackAsync(string callbackName, string? deviceId)
    {
        try
        {
            if (_audioService is IAudioDeviceCacheInvalidator cacheInvalidator)
            {
                if (callbackName == nameof(OnDefaultDeviceChanged) &&
                    cacheInvalidator.TryUpdateDefaultDeviceCache(deviceId, out var cachedDevices))
                {
                    _events.PublishAudioDeviceChanged(DeviceEndpoints.Project(cachedDevices));
                    return;
                }

                if (callbackName == nameof(OnDefaultDeviceChanged))
                {
                    return;
                }

                if (callbackName != nameof(OnDefaultDeviceChanged))
                {
                    cacheInvalidator.InvalidateDeviceCache();
                }
            }

            var devices = await _audioService.ListDevicesAsync().ConfigureAwait(false);
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
        catch (OperationCanceledException ex)
        {
            _logger.LogDebug(ex, "Audio device callback {CallbackName} cancelled during shutdown for {DeviceId}.", callbackName, deviceId);
        }
        catch (ObjectDisposedException ex)
        {
            _logger.LogDebug(ex, "Audio device callback {CallbackName} ignored after audio dispatcher disposal for {DeviceId}.", callbackName, deviceId);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Audio device callback {CallbackName} failed for {DeviceId}.", callbackName, deviceId);
        }
    }

    private readonly record struct DeviceChangeSignal(string CallbackName, string? DeviceId);
}
