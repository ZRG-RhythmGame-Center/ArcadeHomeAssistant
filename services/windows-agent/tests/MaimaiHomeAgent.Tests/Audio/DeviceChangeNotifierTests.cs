using System.Runtime.InteropServices;
using MaimaiHomeAgent.Audio;
using MaimaiHomeAgent.Realtime;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Xunit;

namespace MaimaiHomeAgent.Tests.Audio;

/// <summary>
/// Unit tests for <see cref="DeviceChangeNotifier"/>. Verifies the hosted-service
/// lifecycle (idempotent register/unregister, swallowed COMException on stop)
/// and that callback paths funnel through <see cref="IAudioStaDispatcher"/> +
/// <see cref="EventPublisher"/>.
///
/// Uses <see cref="InlineDispatcher"/> instead of the real AudioStaDispatcher
/// to avoid spinning up an STA thread that can hang the test process.
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
    public async Task OnDefaultDeviceChanged_FunnelsThroughDispatcher_AndPublishesAudioDeviceChanged()
    {
        var source = new Mock<IAudioDeviceNotificationSource>(MockBehavior.Strict);
        source.Setup(s => s.Register(It.IsAny<IAudioDeviceNotificationSink>()));

        var deviceId = Guid.NewGuid();
        var audio = new Mock<IAudioService>(MockBehavior.Strict);
        audio
            .Setup(a => a.ListDevicesAsync())
            .ReturnsAsync(new[]
            {
                new AudioDevice(deviceId, "Speakers", true, DeviceState.Active),
            });

        var hub = new RecordingHub();
        var publisher = new EventPublisher(hub);
        var dispatcher = new InlineDispatcher();

        var notifier = new DeviceChangeNotifier(
            source.Object,
            dispatcher,
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
        var dispatcher = new InlineDispatcher();

        var notifier = new DeviceChangeNotifier(
            source.Object,
            dispatcher,
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
        var dispatcher = new InlineDispatcher();

        var notifier = new DeviceChangeNotifier(
            source.Object,
            dispatcher,
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
        var dispatcher = new InlineDispatcher();

        var notifier = new DeviceChangeNotifier(
            source.Object,
            dispatcher,
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
        var dispatcher = new InlineDispatcher();

        var notifier = new DeviceChangeNotifier(
            source.Object,
            dispatcher,
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
        var dispatcher = new InlineDispatcher();
        var audio = new Mock<IAudioService>(MockBehavior.Loose).Object;
        return (
            new DeviceChangeNotifier(
                source,
                dispatcher,
                audio,
                publisher,
                NullLogger<DeviceChangeNotifier>.Instance),
            hub);
    }

    /// <summary>
    /// Test-only dispatcher that executes work inline on the calling thread.
    /// Implements <see cref="IAudioStaDispatcher"/> directly — no STA thread.
    /// </summary>
    private sealed class InlineDispatcher : IAudioStaDispatcher
    {
        public Task<T> InvokeAsync<T>(Func<Task<T>> work) => work();
        public Task InvokeAsync(Func<Task> work) => work();
    }

    private sealed class RecordingHub : EventHub
    {
        public RecordingHub() : base(NullLogger<EventHub>.Instance) { }

        public List<EventEnvelope> Broadcasts { get; } = new();

        public override Task BroadcastAsync(EventEnvelope envelope, CancellationToken ct = default)
        {
            lock (Broadcasts) { Broadcasts.Add(envelope); }
            return Task.CompletedTask;
        }

        public async Task WaitForBroadcastAsync(int count, TimeSpan timeout)
        {
            var deadline = DateTimeOffset.UtcNow + timeout;
            while (DateTimeOffset.UtcNow < deadline)
            {
                lock (Broadcasts) { if (Broadcasts.Count >= count) return; }
                await Task.Delay(10);
            }
            throw new TimeoutException(
                $"Expected {count} broadcasts within {timeout}; got {Broadcasts.Count}.");
        }
    }
}
