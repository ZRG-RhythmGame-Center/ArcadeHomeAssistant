using System.IO.Pipelines;
using System.Net;
using System.Net.Http.Json;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using MaimaiHomeAgent.Audio;
using MaimaiHomeAgent.Realtime;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;

namespace MaimaiHomeAgent.Tests.Audio;

/// <summary>
///     Integration tests for the /api/audio endpoints registered via
///     <c>AudioEndpoints.MapAudioEndpoints</c>. Uses an in-process TestServer with
///     a mocked <see cref="IAudioService" /> so we never touch real Core Audio COM.
/// </summary>
public class AudioEndpointsTests : IAsyncLifetime
{
    private WebApplication _app = null!;
    private Mock<IAudioService> _audioMock = null!;
    private HttpClient _client = null!;
    private EventHub _hub = null!;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateBuilder();
        _audioMock = new Mock<IAudioService>(MockBehavior.Strict);
        builder.Services.AddSingleton<IAudioService>(_audioMock.Object);
        builder.Services.AddSingleton<EventHub>(sp => new EventHub(NullLogger<EventHub>.Instance));
        builder.Services.AddSingleton<EventPublisher>();
        builder.WebHost.UseTestServer();

        _app = builder.Build();
        _hub = _app.Services.GetRequiredService<EventHub>();
        _app.MapAudioEndpoints();

        await _app.StartAsync();
        _client = _app.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        await _app.StopAsync();
        await _app.DisposeAsync();
        _client.Dispose();
    }

    [Fact]
    public async Task GetState_Returns200WithExpectedShape()
    {
        var deviceId = Guid.NewGuid();
        _audioMock
            .Setup(s => s.GetStateAsync())
            .ReturnsAsync(new AudioState(0.42d, false, deviceId));

        var response = await _client.GetAsync("/api/audio/state");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        Assert.Equal(0.42d, root.GetProperty("masterVolume").GetDouble(), 5);
        Assert.False(root.GetProperty("muted").GetBoolean());
        Assert.Equal(deviceId, root.GetProperty("defaultDeviceId").GetGuid());
    }

    [Fact]
    public async Task GetState_NullDefaultDevice_Returns200WithNull()
    {
        _audioMock
            .Setup(s => s.GetStateAsync())
            .ReturnsAsync(new AudioState(0d, true, null));

        var response = await _client.GetAsync("/api/audio/state");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        Assert.Equal(0d, root.GetProperty("masterVolume").GetDouble());
        Assert.True(root.GetProperty("muted").GetBoolean());
        Assert.Equal(JsonValueKind.Null, root.GetProperty("defaultDeviceId").ValueKind);
    }

    [Fact]
    public async Task PostVolume_ValidLevel_Returns200WithUpdatedState()
    {
        var deviceId = Guid.NewGuid();
        _audioMock
            .Setup(s => s.SetVolumeAsync(0.5d))
            .Returns(Task.CompletedTask);
        _audioMock
            .Setup(s => s.GetStateAsync())
            .ReturnsAsync(new AudioState(0.5d, false, deviceId));

        var response = await _client.PostAsJsonAsync("/api/audio/volume", new { level = 0.5d });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal(0.5d, doc.RootElement.GetProperty("masterVolume").GetDouble(), 5);
        _audioMock.Verify(s => s.SetVolumeAsync(0.5d), Times.Once);
    }

    [Fact]
    public async Task PostVolume_OutOfRangeAbove_Returns400()
    {
        var response = await _client.PostAsJsonAsync("/api/audio/volume", new { level = 1.5d });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("validation_error", doc.RootElement.GetProperty("error").GetString());
        Assert.Equal("level must be between 0 and 1", doc.RootElement.GetProperty("message").GetString());
        _audioMock.Verify(s => s.SetVolumeAsync(It.IsAny<double>()), Times.Never);
    }

    [Fact]
    public async Task PostVolume_OutOfRangeBelow_Returns400()
    {
        var response = await _client.PostAsJsonAsync("/api/audio/volume", new { level = -0.01d });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("validation_error", doc.RootElement.GetProperty("error").GetString());
    }

    [Fact]
    public async Task PostVolume_MissingBody_Returns400()
    {
        // Empty body — no level field present.
        var response = await _client.PostAsync(
            "/api/audio/volume",
            new StringContent("{}", Encoding.UTF8, "application/json"));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        _audioMock.Verify(s => s.SetVolumeAsync(It.IsAny<double>()), Times.Never);
    }

    [Fact]
    public async Task PostMute_True_Returns200WithUpdatedState()
    {
        var deviceId = Guid.NewGuid();
        _audioMock
            .Setup(s => s.SetMuteAsync(true))
            .Returns(Task.CompletedTask);
        _audioMock
            .Setup(s => s.GetStateAsync())
            .ReturnsAsync(new AudioState(0.3d, true, deviceId));

        var response = await _client.PostAsJsonAsync("/api/audio/mute", new { muted = true });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.True(doc.RootElement.GetProperty("muted").GetBoolean());
        _audioMock.Verify(s => s.SetMuteAsync(true), Times.Once);
    }

    [Fact]
    public async Task PostMute_MissingBody_Returns400()
    {
        var response = await _client.PostAsync(
            "/api/audio/mute",
            new StringContent("{}", Encoding.UTF8, "application/json"));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        _audioMock.Verify(s => s.SetMuteAsync(It.IsAny<bool>()), Times.Never);
    }

    [Fact]
    public async Task PostVolume_DispatcherBusy_Returns503WithRetryAfter()
    {
        _audioMock
            .Setup(s => s.SetVolumeAsync(0.5d))
            .ThrowsAsync(new AudioServiceBusyException());

        var response = await _client.PostAsJsonAsync("/api/audio/volume", new { level = 0.5d });

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        Assert.True(response.Headers.TryGetValues("Retry-After", out var values));
        Assert.Equal("1", values!.First());
    }

    [Fact]
    public async Task PostMute_DeviceUnavailable_Returns502()
    {
        _audioMock
            .Setup(s => s.SetMuteAsync(true))
            .ThrowsAsync(new AudioOperationException("no default device"));

        var response = await _client.PostAsJsonAsync("/api/audio/mute", new { muted = true });

        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("device_unavailable", doc.RootElement.GetProperty("error").GetString());
        Assert.Equal("音频设备不可用：no default device", doc.RootElement.GetProperty("message").GetString());
    }

    [Fact]
    public async Task GetState_CanceledOperation_Returns502WithMessage()
    {
        _audioMock
            .Setup(s => s.GetStateAsync())
            .ThrowsAsync(new TaskCanceledException("A task was canceled."));

        var response = await _client.GetAsync("/api/audio/state");

        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("device_unavailable", doc.RootElement.GetProperty("error").GetString());
        Assert.Equal("音频设备不可用：A task was canceled.", doc.RootElement.GetProperty("message").GetString());
    }

    [Fact]
    public async Task GetState_DispatcherBusy_Returns503WithRetryAfter()
    {
        _audioMock
            .Setup(s => s.GetStateAsync())
            .ThrowsAsync(new AudioServiceBusyException());

        var response = await _client.GetAsync("/api/audio/state");

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        Assert.True(response.Headers.TryGetValues("Retry-After", out var values));
        Assert.Equal("1", values!.First());
    }

    [Fact]
    public async Task PostVolume_OnSuccess_BroadcastsAudioStateEvent()
    {
        // Hook an in-memory WebSocket session into the EventHub so we can
        // observe the broadcast that the endpoint should emit on success.
        var (serverWs, clientWs) = CreateLinkedWebSocketPair();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var addTask = Task.Run(() => _hub.AddAsync(serverWs, cts.Token));
        await WaitForAsync(() => _hub.SessionCount >= 1, TimeSpan.FromSeconds(2));

        var deviceId = Guid.NewGuid();
        _audioMock
            .Setup(s => s.SetVolumeAsync(0.7d))
            .Returns(Task.CompletedTask);
        _audioMock
            .Setup(s => s.GetStateAsync())
            .ReturnsAsync(new AudioState(0.7d, false, deviceId));

        var response = await _client.PostAsJsonAsync("/api/audio/volume", new { level = 0.7d });
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        // Read the JSON frame from the client side; it must be an audio.state envelope.
        var received = await ReadTextMessageAsync(clientWs, cts.Token);
        using var doc = JsonDocument.Parse(received);
        Assert.Equal(EventTypes.AudioState, doc.RootElement.GetProperty("type").GetString());
        Assert.Equal(0.7d, doc.RootElement.GetProperty("payload").GetProperty("masterVolume").GetDouble(), 5);

        await clientWs.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None);
        await addTask;
    }

    [Fact]
    public async Task PostMute_OnSuccess_BroadcastsAudioStateEvent()
    {
        var (serverWs, clientWs) = CreateLinkedWebSocketPair();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var addTask = Task.Run(() => _hub.AddAsync(serverWs, cts.Token));
        await WaitForAsync(() => _hub.SessionCount >= 1, TimeSpan.FromSeconds(2));

        _audioMock
            .Setup(s => s.SetMuteAsync(true))
            .Returns(Task.CompletedTask);
        _audioMock
            .Setup(s => s.GetStateAsync())
            .ReturnsAsync(new AudioState(0.4d, true, Guid.NewGuid()));

        var response = await _client.PostAsJsonAsync("/api/audio/mute", new { muted = true });
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var received = await ReadTextMessageAsync(clientWs, cts.Token);
        using var doc = JsonDocument.Parse(received);
        Assert.Equal(EventTypes.AudioState, doc.RootElement.GetProperty("type").GetString());
        Assert.True(doc.RootElement.GetProperty("payload").GetProperty("muted").GetBoolean());

        await clientWs.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None);
        await addTask;
    }

    [Fact]
    public async Task GetState_DoesNotBroadcast()
    {
        // Read-only state retrieval must NOT trigger an audio.state broadcast.
        var (serverWs, clientWs) = CreateLinkedWebSocketPair();
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        var addTask = Task.Run(() => _hub.AddAsync(serverWs, cts.Token));
        await WaitForAsync(() => _hub.SessionCount >= 1, TimeSpan.FromSeconds(2));

        _audioMock
            .Setup(s => s.GetStateAsync())
            .ReturnsAsync(new AudioState(0.5d, false, Guid.NewGuid()));

        var response = await _client.GetAsync("/api/audio/state");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        // Give the system a brief moment in case a broadcast were to occur.
        await Task.Delay(150);
        // Try to read with a short timeout — there should be NO frame.
        var readBuffer = new byte[1024];
        using var readCts = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));
        var threw = false;
        try
        {
            var result = await clientWs.ReceiveAsync(readBuffer, readCts.Token);
            // If we got a frame, ensure it's not an audio.state event.
            if (result.MessageType == WebSocketMessageType.Text)
            {
                var text = Encoding.UTF8.GetString(readBuffer, 0, result.Count);
                using var doc = JsonDocument.Parse(text);
                Assert.NotEqual(EventTypes.AudioState, doc.RootElement.GetProperty("type").GetString());
            }
        }
        catch (OperationCanceledException)
        {
            threw = true;
        }
        catch (WebSocketException)
        {
            threw = true;
        }

        Assert.True(threw, "Expected no broadcast on GET state, but a frame was received.");

        try
        {
            await clientWs.CloseAsync(WebSocketCloseStatus.NormalClosure, "done", CancellationToken.None);
        }
        catch (WebSocketException)
        {
            /* aborted by cancelled receive — fine */
        }

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

        if (!predicate()) throw new TimeoutException("WaitForAsync predicate did not become true in time.");
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
            if (result.EndOfMessage) return Encoding.UTF8.GetString(ms.ToArray());
        }
    }

    private static (WebSocket Server, WebSocket Client) CreateLinkedWebSocketPair()
    {
        var serverToClient = new Pipe();
        var clientToServer = new Pipe();
        var serverStream = new DuplexStream(clientToServer.Reader.AsStream(), serverToClient.Writer.AsStream());
        var clientStream = new DuplexStream(serverToClient.Reader.AsStream(), clientToServer.Writer.AsStream());
        var server = WebSocket.CreateFromStream(serverStream, true, null, TimeSpan.FromMinutes(2));
        var client = WebSocket.CreateFromStream(clientStream, false, null, TimeSpan.FromMinutes(2));
        return (server, client);
    }

    private sealed class DuplexStream : Stream
    {
        private readonly Stream _read;
        private readonly Stream _write;

        public DuplexStream(Stream read, Stream write)
        {
            _read = read;
            _write = write;
        }

        public override bool CanRead => true;
        public override bool CanWrite => true;
        public override bool CanSeek => false;
        public override long Length => throw new NotSupportedException();

        public override long Position
        {
            get => throw new NotSupportedException();
            set => throw new NotSupportedException();
        }

        public override void Flush()
        {
            _write.Flush();
        }

        public override Task FlushAsync(CancellationToken ct)
        {
            return _write.FlushAsync(ct);
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            return _read.Read(buffer, offset, count);
        }

        public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct)
        {
            return _read.ReadAsync(buffer, offset, count, ct);
        }

        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
        {
            return _read.ReadAsync(buffer, ct);
        }

        public override void Write(byte[] buffer, int offset, int count)
        {
            _write.Write(buffer, offset, count);
        }

        public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct)
        {
            return _write.WriteAsync(buffer, offset, count, ct);
        }

        public override ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default)
        {
            return _write.WriteAsync(buffer, ct);
        }

        public override long Seek(long offset, SeekOrigin origin)
        {
            throw new NotSupportedException();
        }

        public override void SetLength(long value)
        {
            throw new NotSupportedException();
        }

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