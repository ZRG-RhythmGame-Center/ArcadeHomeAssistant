using System.Runtime.InteropServices;
using MaimaiHomeAgent.Audio;
using MaimaiHomeAgent.Realtime;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;

namespace MaimaiHomeAgent.Tests.Audio;

/// <summary>
///     Unit tests for <see cref="DeviceChangeNotifier" />. Verifies the hosted-service
///     lifecycle (idempotent register/unregister, swallowed COMException on stop)
///     and that callback paths publish through <see cref="EventPublisher" />.
/// </summary>
public class DeviceChangeNotifierTests
{
    [Fact]
    public async Task StartAsync_RegistersExactlyOnce_AcrossMultipleCalls()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        source.Setup(s => s.Register(It.IsAny<IAudioDeviceNotificationSink>()));

        var (notifier, _) = BuildNotifier(source.Object);

        await notifier.StartAsync(CancellationToken.None);
        await notifier.StartAsync(CancellationToken.None);
        await notifier.StartAsync(CancellationToken.None);

        source.Verify(
            s => s.Register(It.IsAny<IAudioDeviceNotificationSink>()),
            Times.Once);
    }

    [Fact]
    public async Task StopAsync_WithoutStart_DoesNotCallUnregister()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var (notifier, _) = BuildNotifier(source.Object);

        await notifier.StopAsync(CancellationToken.None);

        source.Verify(
            s => s.Unregister(It.IsAny<IAudioDeviceNotificationSink>()),
            Times.Never);
    }

    [Fact]
    public async Task StopAsync_AfterStart_UnregistersOnce()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        source.Setup(s => s.Register(It.IsAny<IAudioDeviceNotificationSink>()));
        source.Setup(s => s.Unregister(It.IsAny<IAudioDeviceNotificationSink>()));

        var (notifier, _) = BuildNotifier(source.Object);
        await notifier.StartAsync(CancellationToken.None);

        await notifier.StopAsync(CancellationToken.None);

        source.Verify(
            s => s.Unregister(It.IsAny<IAudioDeviceNotificationSink>()),
            Times.Once);
    }

    [Fact]
    public async Task StopAsync_TwiceIsIdempotent()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        source.Setup(s => s.Register(It.IsAny<IAudioDeviceNotificationSink>()));
        source.Setup(s => s.Unregister(It.IsAny<IAudioDeviceNotificationSink>()));

        var (notifier, _) = BuildNotifier(source.Object);
        await notifier.StartAsync(CancellationToken.None);
        await notifier.StopAsync(CancellationToken.None);
        await notifier.StopAsync(CancellationToken.None);

        source.Verify(
            s => s.Unregister(It.IsAny<IAudioDeviceNotificationSink>()),
            Times.Once);
    }

    [Fact]
    public async Task StopAsync_SwallowsCOMExceptionFromUnregister()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        source.Setup(s => s.Register(It.IsAny<IAudioDeviceNotificationSink>()));
        source
            .Setup(s => s.Unregister(It.IsAny<IAudioDeviceNotificationSink>()))
            .Throws(new COMException("device removed"));

        var (notifier, _) = BuildNotifier(source.Object);
        await notifier.StartAsync(CancellationToken.None);

        // Must not throw even though Unregister blew up.
        await notifier.StopAsync(CancellationToken.None);

        source.Verify(
            s => s.Unregister(It.IsAny<IAudioDeviceNotificationSink>()),
            Times.Once);
    }

    [Fact]
    public async Task OnDefaultDeviceChanged_PublishesAudioDeviceChanged()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        source.Setup(s => s.Register(It.IsAny<IAudioDeviceNotificationSink>()));

        var deviceId = Guid.NewGuid();
        var audio = new Mock<IAudioService>(MockBehavior.Strict);
        audio
            .Setup(a => a.ListDevicesAsync())
            .ReturnsAsync(new[]
            {
                new AudioDevice(deviceId, "Speakers", true, DeviceState.Active)
            });

        var hub = new RecordingHub();
        var publisher = new EventPublisher(hub);
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio.Object,
            publisher,
            NullLogger<DeviceChangeNotifier>.Instance);

        // Fire the callback synchronously (HandleCallbackAsync is fire-and-forget).
        notifier.OnDefaultDeviceChanged("device-id");
        await hub.WaitForBroadcastAsync(1, TimeSpan.FromSeconds(5));

        var envelope = Assert.Single(hub.Broadcasts);
        Assert.Equal(EventTypes.AudioDeviceChanged, envelope.Type);
        Assert.Equal(deviceId.ToString(), envelope.Payload[0].GetProperty("id").GetString());
        audio.Verify(a => a.ListDevicesAsync(), Times.Once);
    }

    [Fact]
    public async Task OnDeviceAdded_TriggersBroadcast()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var audio = new Mock<IAudioService>(MockBehavior.Strict);
        audio
            .Setup(a => a.ListDevicesAsync())
            .ReturnsAsync(Array.Empty<AudioDevice>());

        var hub = new RecordingHub();
        var publisher = new EventPublisher(hub);
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio.Object,
            publisher,
            NullLogger<DeviceChangeNotifier>.Instance);

        notifier.OnDeviceAdded("new-id");
        await hub.WaitForBroadcastAsync(1, TimeSpan.FromSeconds(5));

        Assert.Single(hub.Broadcasts);
        Assert.Equal(EventTypes.AudioDeviceChanged, hub.Broadcasts[0].Type);
    }

    [Fact]
    public async Task OnDeviceRemoved_TriggersBroadcast()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var audio = new Mock<IAudioService>(MockBehavior.Strict);
        audio
            .Setup(a => a.ListDevicesAsync())
            .ReturnsAsync(Array.Empty<AudioDevice>());

        var hub = new RecordingHub();
        var publisher = new EventPublisher(hub);
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio.Object,
            publisher,
            NullLogger<DeviceChangeNotifier>.Instance);

        notifier.OnDeviceRemoved("gone-id");
        await hub.WaitForBroadcastAsync(1, TimeSpan.FromSeconds(5));

        Assert.Single(hub.Broadcasts);
    }

    [Fact]
    public async Task OnDeviceStateChanged_TriggersBroadcast()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var audio = new Mock<IAudioService>(MockBehavior.Strict);
        audio
            .Setup(a => a.ListDevicesAsync())
            .ReturnsAsync(Array.Empty<AudioDevice>());

        var hub = new RecordingHub();
        var publisher = new EventPublisher(hub);
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio.Object,
            publisher,
            NullLogger<DeviceChangeNotifier>.Instance);

        notifier.OnDeviceStateChanged("state-id");
        await hub.WaitForBroadcastAsync(1, TimeSpan.FromSeconds(5));

        Assert.Single(hub.Broadcasts);
    }

    [Fact]
    public async Task Callback_WhenListDevicesThrowsCOMException_DoesNotPropagate()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var audio = new Mock<IAudioService>(MockBehavior.Strict);
        audio
            .Setup(a => a.ListDevicesAsync())
            .ThrowsAsync(new COMException("simulated COM failure"));

        var hub = new RecordingHub();
        var publisher = new EventPublisher(hub);
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio.Object,
            publisher,
            NullLogger<DeviceChangeNotifier>.Instance);

        notifier.OnDeviceAdded("id");
        // No broadcast should occur, but neither should we get an unobserved exception.
        await Task.Delay(150);

        Assert.Empty(hub.Broadcasts);
    }

    // ---------------- Helpers ----------------

    private static (DeviceChangeNotifier Notifier, RecordingHub Hub) BuildNotifier(
        IAudioDeviceNotificationSource source)
    {
        var hub = new RecordingHub();
        var publisher = new EventPublisher(hub);
        var audio = new Mock<IAudioService>(MockBehavior.Loose).Object;
        return (
            new DeviceChangeNotifier(
                source,
                audio,
                publisher,
                NullLogger<DeviceChangeNotifier>.Instance),
            hub);
    }

    [Fact]
    public async Task OnDefaultDeviceChanged_DoesNotListDevicesInline()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var audio = new Mock<IAudioService>(MockBehavior.Strict);
        var listStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        audio
            .Setup(a => a.ListDevicesAsync())
            .ReturnsAsync(() =>
            {
                listStarted.TrySetResult();
                return Array.Empty<AudioDevice>();
            });

        var hub = new RecordingHub();
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio.Object,
            new EventPublisher(hub),
            NullLogger<DeviceChangeNotifier>.Instance);

        notifier.OnDefaultDeviceChanged("device-id");

        Assert.False(listStarted.Task.IsCompleted);
        await listStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));
    }

    [Fact]
    public async Task MultipleCallbacks_AreCoalescedIntoSingleDeviceListRefresh()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var audio = new Mock<IAudioService>(MockBehavior.Strict);
        audio
            .Setup(a => a.ListDevicesAsync())
            .ReturnsAsync(Array.Empty<AudioDevice>());

        var hub = new RecordingHub();
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio.Object,
            new EventPublisher(hub),
            NullLogger<DeviceChangeNotifier>.Instance);

        notifier.OnDeviceAdded("new-id");
        notifier.OnDeviceRemoved("gone-id");
        notifier.OnDeviceStateChanged("state-id");
        notifier.OnDefaultDeviceChanged("default-id");

        await hub.WaitForBroadcastAsync(1, TimeSpan.FromSeconds(5));
        await Task.Delay(300);

        Assert.Single(hub.Broadcasts);
        audio.Verify(a => a.ListDevicesAsync(), Times.Once);
    }

    [Fact]
    public async Task Callback_InvalidatesDeviceCacheBeforeRefresh()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var audio = new CacheInvalidatingAudioService();
        var hub = new RecordingHub();
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio,
            new EventPublisher(hub),
            NullLogger<DeviceChangeNotifier>.Instance);

        notifier.OnDeviceAdded("new-id");
        await hub.WaitForBroadcastAsync(1, TimeSpan.FromSeconds(5));

        Assert.Equal(1, audio.InvalidateCount);
        Assert.Equal(1, audio.ListDevicesCount);
        Assert.True(audio.InvalidatedBeforeListDevices);
    }

    [Fact]
    public async Task DefaultDeviceChanged_WhenCached_UpdatesCacheWithoutRefreshingDevices()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var defaultDevice = Guid.NewGuid();
        var audio = new CacheInvalidatingAudioService
        {
            CachedDevices = new[]
            {
                new AudioDevice(defaultDevice, "Speakers", false, DeviceState.Active)
            }
        };
        var hub = new RecordingHub();
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio,
            new EventPublisher(hub),
            NullLogger<DeviceChangeNotifier>.Instance);

        notifier.OnDefaultDeviceChanged($"{{0.0.0.00000000}}.{{{defaultDevice}}}");
        await hub.WaitForBroadcastAsync(1, TimeSpan.FromSeconds(5));

        Assert.Equal(0, audio.InvalidateCount);
        Assert.Equal(0, audio.ListDevicesCount);
        var envelope = Assert.Single(hub.Broadcasts);
        Assert.True(envelope.Payload[0].GetProperty("isDefault").GetBoolean());
    }

    [Fact]
    public async Task DefaultDeviceChanged_WhenCacheMissing_DoesNotRefreshDevices()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        var audio = new CacheInvalidatingAudioService();
        var hub = new RecordingHub();
        var notifier = new DeviceChangeNotifier(
            source.Object,
            audio,
            new EventPublisher(hub),
            NullLogger<DeviceChangeNotifier>.Instance);

        notifier.OnDefaultDeviceChanged($"{{0.0.0.00000000}}.{{{Guid.NewGuid()}}}");
        await Task.Delay(300);

        Assert.Equal(0, audio.InvalidateCount);
        Assert.Equal(0, audio.ListDevicesCount);
        Assert.Empty(hub.Broadcasts);
    }

    private sealed class RecordingHub : EventHub
    {
        public RecordingHub() : base(NullLogger<EventHub>.Instance)
        {
        }

        public List<EventEnvelope> Broadcasts { get; } = new();

        public override Task BroadcastAsync(EventEnvelope envelope, CancellationToken ct = default)
        {
            lock (Broadcasts)
            {
                Broadcasts.Add(envelope);
            }

            return Task.CompletedTask;
        }

        public async Task WaitForBroadcastAsync(int count, TimeSpan timeout)
        {
            var deadline = DateTimeOffset.UtcNow + timeout;
            while (DateTimeOffset.UtcNow < deadline)
            {
                lock (Broadcasts)
                {
                    if (Broadcasts.Count >= count) return;
                }

                await Task.Delay(10);
            }

            throw new TimeoutException(
                $"Expected {count} broadcasts within {timeout}; got {Broadcasts.Count}.");
        }
    }

    private sealed class CacheInvalidatingAudioService : IAudioService, IAudioDeviceCacheInvalidator
    {
        private bool _invalidated;

        public int InvalidateCount { get; private set; }
        public int ListDevicesCount { get; private set; }
        public bool InvalidatedBeforeListDevices { get; private set; }
        public IReadOnlyList<AudioDevice>? CachedDevices { get; init; }

        public void InvalidateDeviceCache()
        {
            InvalidateCount++;
            _invalidated = true;
        }

        public bool TryUpdateDefaultDeviceCache(string? endpointId, out IReadOnlyList<AudioDevice> devices)
        {
            if (CachedDevices is null || string.IsNullOrWhiteSpace(endpointId))
            {
                devices = Array.Empty<AudioDevice>();
                return false;
            }

            var start = endpointId.LastIndexOf('{');
            var end = endpointId.LastIndexOf('}');
            var defaultDeviceId = Guid.Parse(endpointId.Substring(start + 1, end - start - 1));
            devices = CachedDevices
                .Select(device => device with { IsDefault = device.Id == defaultDeviceId })
                .ToArray();
            return true;
        }

        public Task<AudioState> GetStateAsync()
        {
            throw new NotSupportedException();
        }

        public Task SetVolumeAsync(double level)
        {
            throw new NotSupportedException();
        }

        public Task SetMuteAsync(bool muted)
        {
            throw new NotSupportedException();
        }

        public Task SetDefaultDeviceAsync(Guid deviceId)
        {
            throw new NotSupportedException();
        }

        public Task<IReadOnlyList<AudioDevice>> ListDevicesAsync()
        {
            ListDevicesCount++;
            InvalidatedBeforeListDevices = _invalidated;
            return Task.FromResult<IReadOnlyList<AudioDevice>>(Array.Empty<AudioDevice>());
        }
    }
}