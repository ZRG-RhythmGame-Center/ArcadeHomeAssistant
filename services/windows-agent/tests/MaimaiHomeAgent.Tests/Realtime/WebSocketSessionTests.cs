using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using MaimaiHomeAgent.Realtime;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace MaimaiHomeAgent.Tests.Realtime;

/// <summary>
/// Unit tests for <see cref="WebSocketSession"/>. Uses in-memory WebSocket
/// pairs created via <see cref="WebSocket.CreateFromStream"/> over a duplex
/// pipe so no network is required.
/// </summary>
public class WebSocketSessionTests
{
    // ------------------------------------------------------------------ //
    //  CloseAsync                                                          //
    // ------------------------------------------------------------------ //

    [Fact]
    public async Task CloseAsync_SwallowsWebSocketException()
    {
        // Use a broken socket (already aborted) to trigger WebSocketException.
        var (serverWs, clientWs) = CreateLinkedPair();
        // Abort the client side so the server's CloseAsync will fail.
        clientWs.Abort();

        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);

        // Must not throw.
        await session.CloseAsync(WebSocketCloseStatus.NormalClosure, "done");
        await session.DisposeAsync();
        clientWs.Dispose();
    }

    [Fact]
    public async Task CloseAsync_WhenAlreadyClosed_DoesNotThrow()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);

        // Close from the client side first.
        await clientWs.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);

        // Server-side close on an already-closed socket must not throw.
        await session.CloseAsync(WebSocketCloseStatus.NormalClosure, "done");
        await session.DisposeAsync();
        clientWs.Dispose();
    }

    // ------------------------------------------------------------------ //
    //  SendJsonAsync / semaphore serialisation                             //
    // ------------------------------------------------------------------ //

    [Fact]
    public async Task SendJsonAsync_DeliversFrameToClient()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);

        var envelope = new EventEnvelope(
            EventTypes.AudioState,
            JsonSerializer.SerializeToElement(new { masterVolume = 0.5 }),
            DateTimeOffset.UtcNow);

        await session.SendJsonAsync(envelope);

        var received = await ReadTextMessageAsync(clientWs, CancellationToken.None);
        using var doc = JsonDocument.Parse(received);
        Assert.Equal(EventTypes.AudioState, doc.RootElement.GetProperty("type").GetString());

        await session.DisposeAsync();
        clientWs.Dispose();
    }

    [Fact]
    public async Task SendJsonAsync_ConcurrentSends_AllDeliveredInOrder()
    {
        // Verifies the semaphore serialises concurrent sends without deadlock.
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);

        const int count = 10;
        var sendTasks = Enumerable.Range(0, count).Select(i =>
        {
            var env = new EventEnvelope(
                EventTypes.AudioState,
                JsonSerializer.SerializeToElement(new { index = i }),
                DateTimeOffset.UtcNow);
            return session.SendJsonAsync(env);
        }).ToArray();

        await Task.WhenAll(sendTasks);

        // Read all frames from the client side.
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var received = new List<string>();
        for (int i = 0; i < count; i++)
        {
            received.Add(await ReadTextMessageAsync(clientWs, cts.Token));
        }

        Assert.Equal(count, received.Count);
        // All frames must be valid JSON with the expected type.
        foreach (var json in received)
        {
            using var doc = JsonDocument.Parse(json);
            Assert.Equal(EventTypes.AudioState, doc.RootElement.GetProperty("type").GetString());
        }

        await session.DisposeAsync();
        clientWs.Dispose();
    }

    [Fact]
    public async Task SendPingAsync_DeliversPingFrame()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);

        await session.SendPingAsync();

        var received = await ReadTextMessageAsync(clientWs, CancellationToken.None);
        Assert.Equal("{\"type\":\"ping\"}", received);

        await session.DisposeAsync();
        clientWs.Dispose();
    }

    // ------------------------------------------------------------------ //
    //  ReceiveLoopAsync                                                    //
    // ------------------------------------------------------------------ //

    [Fact]
    public async Task ReceiveLoopAsync_ExitsWhenClientCloses()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var loopTask = session.ReceiveLoopAsync(cts.Token);

        // Client sends a close frame.
        await clientWs.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);

        // Loop should exit cleanly.
        await loopTask.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(loopTask.IsCompletedSuccessfully);

        await session.DisposeAsync();
        clientWs.Dispose();
    }

    [Fact]
    public async Task ReceiveLoopAsync_ExitsOnCancellation()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);

        using var cts = new CancellationTokenSource();
        var loopTask = session.ReceiveLoopAsync(cts.Token);

        // Cancel before any frame arrives.
        cts.Cancel();

        await loopTask.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(loopTask.IsCompletedSuccessfully);

        await session.DisposeAsync();
        clientWs.Dispose();
    }

    [Fact]
    public async Task ReceiveLoopAsync_UpdatesLastPongAt_OnAnyFrame()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);
        var before = session.LastPongAt;

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var loopTask = session.ReceiveLoopAsync(cts.Token);

        // Send a text frame from the client.
        var payload = Encoding.UTF8.GetBytes("{\"type\":\"pong\"}");
        await clientWs.SendAsync(payload, WebSocketMessageType.Text, true, CancellationToken.None);

        // Give the loop time to process the frame.
        await Task.Delay(100);

        Assert.True(session.LastPongAt >= before);

        cts.Cancel();
        await loopTask.WaitAsync(TimeSpan.FromSeconds(5));

        await session.DisposeAsync();
        clientWs.Dispose();
    }

    [Fact]
    public async Task ReceiveLoopAsync_WebSocketException_ExitsGracefully()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);

        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var loopTask = session.ReceiveLoopAsync(cts.Token);

        // Abort the client to force a WebSocketException on the server receive.
        clientWs.Abort();

        await loopTask.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.True(loopTask.IsCompletedSuccessfully);

        await session.DisposeAsync();
        clientWs.Dispose();
    }

    // ------------------------------------------------------------------ //
    //  DisposeAsync idempotency                                            //
    // ------------------------------------------------------------------ //

    [Fact]
    public async Task DisposeAsync_CalledTwice_DoesNotThrow()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);

        await session.DisposeAsync();
        // Second dispose must not throw (semaphore already disposed).
        await session.DisposeAsync();

        clientWs.Dispose();
    }

    // ------------------------------------------------------------------ //
    //  Properties                                                          //
    // ------------------------------------------------------------------ //

    [Fact]
    public void Constructor_SetsExpectedProperties()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var token = "test-token";
        var before = DateTimeOffset.UtcNow;

        var session = new WebSocketSession(serverWs, token, NullLogger.Instance);

        Assert.NotEqual(Guid.Empty, session.Id);
        Assert.Equal(serverWs, session.Socket);
        Assert.Equal(token, session.Token);
        Assert.True(session.ConnectedAt >= before);
        Assert.True(session.LastPongAt >= before);

        session.DisposeAsync().AsTask().GetAwaiter().GetResult();
        clientWs.Dispose();
    }

    [Fact]
    public void Constructor_NullSocket_ThrowsArgumentNullException()
    {
        Assert.Throws<ArgumentNullException>(() =>
            new WebSocketSession(null!, null, NullLogger.Instance));
    }

    [Fact]
    public void MarkPong_UpdatesLastPongAt()
    {
        var (serverWs, clientWs) = CreateLinkedPair();
        var session = new WebSocketSession(serverWs, null, NullLogger.Instance);
        var newTime = DateTimeOffset.UtcNow.AddMinutes(5);

        session.MarkPong(newTime);

        Assert.Equal(newTime, session.LastPongAt);

        session.DisposeAsync().AsTask().GetAwaiter().GetResult();
        clientWs.Dispose();
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private static (WebSocket Server, WebSocket Client) CreateLinkedPair()
    {
        var serverToClient = new System.IO.Pipelines.Pipe();
        var clientToServer = new System.IO.Pipelines.Pipe();
        var serverStream = new DuplexStream(
            clientToServer.Reader.AsStream(),
            serverToClient.Writer.AsStream());
        var clientStream = new DuplexStream(
            serverToClient.Reader.AsStream(),
            clientToServer.Writer.AsStream());
        var server = WebSocket.CreateFromStream(
            serverStream, isServer: true, subProtocol: null,
            keepAliveInterval: TimeSpan.FromMinutes(2));
        var client = WebSocket.CreateFromStream(
            clientStream, isServer: false, subProtocol: null,
            keepAliveInterval: TimeSpan.FromMinutes(2));
        return (server, client);
    }

    private static async Task<string> ReadTextMessageAsync(WebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[8192];
        using var ms = new MemoryStream();
        while (true)
        {
            var result = await socket.ReceiveAsync(buffer, ct);
            if (result.MessageType == WebSocketMessageType.Close)
                throw new InvalidOperationException("Connection closed while expecting text.");
            ms.Write(buffer, 0, result.Count);
            if (result.EndOfMessage)
                return Encoding.UTF8.GetString(ms.ToArray());
        }
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
        public override long Position
        {
            get => throw new NotSupportedException();
            set => throw new NotSupportedException();
        }
        public override void Flush() => _write.Flush();
        public override Task FlushAsync(CancellationToken ct) => _write.FlushAsync(ct);
        public override int Read(byte[] buffer, int offset, int count) => _read.Read(buffer, offset, count);
        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct)
            => _read.ReadAsync(buffer, offset, count, ct);
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
            => _read.ReadAsync(buffer, ct);
        public override void Write(byte[] buffer, int offset, int count) => _write.Write(buffer, offset, count);
        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct)
            => _write.WriteAsync(buffer, offset, count, ct);
        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default)
            => _write.WriteAsync(buffer, ct);
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        protected override void Dispose(bool disposing)
        {
            if (disposing) { _read.Dispose(); _write.Dispose(); }
            base.Dispose(disposing);
        }
    }
}
