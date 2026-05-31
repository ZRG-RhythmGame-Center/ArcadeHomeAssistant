using System.Text.Json;
using MaimaiHomeAgent.Audio;
using MaimaiHomeAgent.Realtime;
using Microsoft.Extensions.Logging.Abstractions;

namespace MaimaiHomeAgent.Tests.Realtime;

/// <summary>
/// Unit tests for <see cref="EventPublisher"/>. The publisher is a thin
/// domain-semantic wrapper around <see cref="EventHub"/>. We verify each
/// publish method emits an envelope with the correct event type and payload
/// shape by capturing broadcasts on a recording subclass of EventHub —
/// this avoids spinning up real WebSocket pairs purely for assertion.
/// </summary>
public sealed class EventPublisherTests
{
    [Fact]
    public async Task PublishAudioStateChanged_BroadcastsAudioStateEnvelope()
    {
        var hub = new RecordingEventHub();
        var publisher = new EventPublisher(hub);
        var state = new AudioStateDto(0.42, false, Guid.NewGuid());

        publisher.PublishAudioStateChanged(state);
        await hub.WaitForBroadcastAsync(1);

        var envelope = Assert.Single(hub.Broadcasts);
        Assert.Equal(EventTypes.AudioState, envelope.Type);
        Assert.Equal(0.42, envelope.Payload.GetProperty("masterVolume").GetDouble());
        Assert.False(envelope.Payload.GetProperty("muted").GetBoolean());
    }

    [Fact]
    public async Task PublishAudioDeviceChanged_BroadcastsDeviceChangedEnvelope()
    {
        var hub = new RecordingEventHub();
        var publisher = new EventPublisher(hub);
        var devices = new[]
        {
            new DeviceResponse("11111111-1111-1111-1111-111111111111", "Speakers", true, "active"),
            new DeviceResponse("22222222-2222-2222-2222-222222222222", "Headphones", false, "active"),
        };

        publisher.PublishAudioDeviceChanged(devices);
        await hub.WaitForBroadcastAsync(1);

        var envelope = Assert.Single(hub.Broadcasts);
        Assert.Equal(EventTypes.AudioDeviceChanged, envelope.Type);
        Assert.Equal(2, envelope.Payload.GetArrayLength());
        Assert.Equal("Speakers", envelope.Payload[0].GetProperty("name").GetString());
    }

    [Theory]
    [InlineData(EventTypes.FileCreated)]
    [InlineData(EventTypes.FileDeleted)]
    [InlineData(EventTypes.FileRenamed)]
    [InlineData(EventTypes.FileMoved)]
    public async Task PublishFileEvent_BroadcastsRequestedTypeWithFilePayload(string eventType)
    {
        var hub = new RecordingEventHub();
        var publisher = new EventPublisher(hub);
        var payload = new FileEventDto("downloads", "old/file.txt", NewPath: "new/file.txt");

        publisher.PublishFileEvent(eventType, payload);
        await hub.WaitForBroadcastAsync(1);

        var envelope = Assert.Single(hub.Broadcasts);
        Assert.Equal(eventType, envelope.Type);
        Assert.Equal("downloads", envelope.Payload.GetProperty("rootId").GetString());
        Assert.Equal("old/file.txt", envelope.Payload.GetProperty("path").GetString());
        Assert.Equal("new/file.txt", envelope.Payload.GetProperty("newPath").GetString());
    }

    [Fact]
    public async Task PublishDeviceUnavailable_BroadcastsDeviceUnavailableEnvelope()
    {
        var hub = new RecordingEventHub();
        var publisher = new EventPublisher(hub);

        publisher.PublishDeviceUnavailable("device-xyz");
        await hub.WaitForBroadcastAsync(1);

        var envelope = Assert.Single(hub.Broadcasts);
        Assert.Equal(EventTypes.DeviceUnavailable, envelope.Type);
        Assert.Equal("device-xyz", envelope.Payload.GetProperty("deviceId").GetString());
    }

    [Fact]
    public async Task PublishAudioStateChanged_DoesNotBlockCaller()
    {
        // The hub broadcast intentionally hangs; the publisher must still return
        // immediately because BroadcastAsync is fire-and-forget.
        var hub = new HangingEventHub();
        var publisher = new EventPublisher(hub);
        var state = new AudioStateDto(0.5, false, Guid.NewGuid());

        var task = Task.Run(() => publisher.PublishAudioStateChanged(state));
        var completed = await Task.WhenAny(task, Task.Delay(TimeSpan.FromSeconds(1)));
        Assert.Same(task, completed);
    }

    // ---------------- Helpers ----------------

    private sealed class RecordingEventHub : EventHub
    {
        public RecordingEventHub() : base(NullLogger<EventHub>.Instance) { }

        public List<EventEnvelope> Broadcasts { get; } = new();

        public override Task BroadcastAsync(EventEnvelope envelope, CancellationToken ct = default)
        {
            lock (Broadcasts) { Broadcasts.Add(envelope); }
            return Task.CompletedTask;
        }

        public async Task WaitForBroadcastAsync(int count, TimeSpan? timeout = null)
        {
            var deadline = DateTimeOffset.UtcNow + (timeout ?? TimeSpan.FromSeconds(2));
            while (DateTimeOffset.UtcNow < deadline)
            {
                lock (Broadcasts) { if (Broadcasts.Count >= count) return; }
                await Task.Delay(5);
            }
            throw new TimeoutException($"Expected {count} broadcasts within deadline; got {Broadcasts.Count}.");
        }
    }

    private sealed class HangingEventHub : EventHub
    {
        public HangingEventHub() : base(NullLogger<EventHub>.Instance) { }

        public override Task BroadcastAsync(EventEnvelope envelope, CancellationToken ct = default)
            => Task.Delay(TimeSpan.FromMinutes(1), ct);
    }
}
