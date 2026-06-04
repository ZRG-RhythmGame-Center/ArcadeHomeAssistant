using System.IO.Pipelines;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using MaimaiHomeAgent.Realtime;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Tests.Realtime;

public sealed class EventHubTests
{
    private static EventEnvelope SampleEnvelope(string type = "audio.state")
    {
        var payload = JsonSerializer.SerializeToElement(new { foo = "bar", level = 42 });
        return new EventEnvelope(type, payload, DateTimeOffset.UtcNow);
    }

    [Fact]
    public async Task AddAsync_ThenBroadcast_ClientReceivesEnvelope()
    {
        // Arrange
        var (serverWs, clientWs) = CreateLinkedWebSocketPair();
        var hub = new EventHub(NullLogger<EventHub>.Instance);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));

        // Run AddAsync in background; it should register the session and run receive loop.
        var addTask = Task.Run(() => hub.AddAsync(serverWs, cts.Token));

        // Wait for the session to land in the registry.
        await WaitForAsync(() => hub.SessionCount >= 1, TimeSpan.FromSeconds(2));
        Assert.Equal(1, hub.SessionCount);

        var envelope = SampleEnvelope();

        // Act
        await hub.BroadcastAsync(envelope, cts.Token);

        // Assert: client receives JSON envelope
        var received = await ReadTextMessageAsync(clientWs, cts.Token);
        using var doc = JsonDocument.Parse(received);
        Assert.Equal(envelope.Type, doc.RootElement.GetProperty("type").GetString());
        Assert.Equal("bar", doc.RootElement.GetProperty("payload").GetProperty("foo").GetString());

        // Cleanup
        await clientWs.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None);
        await addTask;
    }

    [Fact]
    public async Task TwoConnections_BothReceiveBroadcast()
    {
        var (serverA, clientA) = CreateLinkedWebSocketPair();
        var (serverB, clientB) = CreateLinkedWebSocketPair();
        var hub = new EventHub(NullLogger<EventHub>.Instance);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));

        var addA = Task.Run(() => hub.AddAsync(serverA, cts.Token));
        var addB = Task.Run(() => hub.AddAsync(serverB, cts.Token));

        await WaitForAsync(() => hub.SessionCount >= 2, TimeSpan.FromSeconds(2));
        Assert.Equal(2, hub.SessionCount);

        var envelope = SampleEnvelope("file.created");
        await hub.BroadcastAsync(envelope, cts.Token);

        var msgA = await ReadTextMessageAsync(clientA, cts.Token);
        var msgB = await ReadTextMessageAsync(clientB, cts.Token);

        using var docA = JsonDocument.Parse(msgA);
        using var docB = JsonDocument.Parse(msgB);
        Assert.Equal("file.created", docA.RootElement.GetProperty("type").GetString());
        Assert.Equal("file.created", docB.RootElement.GetProperty("type").GetString());

        await clientA.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None);
        await clientB.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None);
        await Task.WhenAll(addA, addB);
    }

    [Fact]
    public async Task HeartbeatTimeout_RemovesConnection()
    {
        var (serverWs, clientWs) = CreateLinkedWebSocketPair();
        var hub = new EventHub(NullLogger<EventHub>.Instance);
        var options = Options.Create(new HeartbeatOptions
        {
            PingInterval = TimeSpan.FromMilliseconds(50),
            PongTimeout = TimeSpan.FromMilliseconds(100),
        });
        var heartbeat = new HeartbeatService(hub, options, NullLogger<HeartbeatService>.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var addTask = Task.Run(() => hub.AddAsync(serverWs, cts.Token));
        await WaitForAsync(() => hub.SessionCount >= 1, TimeSpan.FromSeconds(2));
        Assert.Equal(1, hub.SessionCount);

        // Force the session's LastPongAt into the past so it is stale.
        var session = Assert.Single(hub.Snapshot());
        session.OverrideLastPongAtForTests(DateTimeOffset.UtcNow - TimeSpan.FromMinutes(5));

        // Tick the heartbeat: should detect timeout and remove the session.
        await heartbeat.RunOnceAsync(DateTimeOffset.UtcNow, cts.Token);

        // Allow the receive loop to unwind after the close.
        await WaitForAsync(() => hub.SessionCount == 0, TimeSpan.FromSeconds(2));
        Assert.Equal(0, hub.SessionCount);

        try { await clientWs.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None); }
        catch { /* connection may already be closed */ }
        await addTask;
    }

    [Fact]
    public async Task RemoveAsync_ThenBroadcast_DoesNotThrow()
    {
        var (serverWs, clientWs) = CreateLinkedWebSocketPair();
        var hub = new EventHub(NullLogger<EventHub>.Instance);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));

        var addTask = Task.Run(() => hub.AddAsync(serverWs, cts.Token));
        await WaitForAsync(() => hub.SessionCount >= 1, TimeSpan.FromSeconds(2));

        var session = Assert.Single(hub.Snapshot());
        await hub.RemoveAsync(session.Id, CancellationToken.None);

        // Broadcasting after removal should be a no-op and must not throw.
        var ex = await Record.ExceptionAsync(() => hub.BroadcastAsync(SampleEnvelope(), cts.Token));
        Assert.Null(ex);
        Assert.Equal(0, hub.SessionCount);

        try { await clientWs.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None); }
        catch { /* connection may already be closed */ }
        await addTask;
    }

    // ---------------- Helpers ----------------

    private static async Task WaitForAsync(Func<bool> predicate, TimeSpan timeout)
    {
        var deadline = DateTimeOffset.UtcNow + timeout;
        while (DateTimeOffset.UtcNow < deadline)
        {
            if (predicate()) return;
            await Task.Delay(10);
        }
        if (!predicate())
        {
            throw new TimeoutException("WaitForAsync predicate did not become true in time.");
        }
    }

    private static async Task<string> ReadTextMessageAsync(WebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[8192];
        using var ms = new MemoryStream();
        while (true)
        {
            var result = await socket.ReceiveAsync(buffer, ct);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                throw new InvalidOperationException("Connection closed while expecting text.");
            }
            ms.Write(buffer, 0, result.Count);
            if (result.EndOfMessage)
            {
                return Encoding.UTF8.GetString(ms.ToArray());
            }
        }
    }

    private static (WebSocket Server, WebSocket Client) CreateLinkedWebSocketPair()
    {
        // Two pipes form a full-duplex channel between server and client WebSockets.
        var serverToClient = new Pipe();
        var clientToServer = new Pipe();

        var serverStream = new DuplexStream(clientToServer.Reader.AsStream(), serverToClient.Writer.AsStream());
        var clientStream = new DuplexStream(serverToClient.Reader.AsStream(), clientToServer.Writer.AsStream());

        var server = WebSocket.CreateFromStream(serverStream, isServer: true, subProtocol: null, keepAliveInterval: TimeSpan.FromMinutes(2));
        var client = WebSocket.CreateFromStream(clientStream, isServer: false, subProtocol: null, keepAliveInterval: TimeSpan.FromMinutes(2));
        return (server, client);
    }

    private sealed class DuplexStream : Stream
    {
        private readonly Stream _read;
        private readonly Stream _write;
        public DuplexStream(Stream read, Stream write) { _read = read; _write = write; }
        public override bool CanRead => true;
        public override bool CanWrite => true;
        public override bool CanSeek => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => _write.Flush();
        public override Task FlushAsync(CancellationToken ct) => _write.FlushAsync(ct);
        public override int Read(byte[] buffer, int offset, int count) => _read.Read(buffer, offset, count);
        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) => _read.ReadAsync(buffer, offset, count, ct);
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default) => _read.ReadAsync(buffer, ct);
        public override void Write(byte[] buffer, int offset, int count) => _write.Write(buffer, offset, count);
        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct) => _write.WriteAsync(buffer, offset, count, ct);
        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default) => _write.WriteAsync(buffer, ct);
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _read.Dispose();
                _write.Dispose();
            }
            base.Dispose(disposing);
        }
    }
}
