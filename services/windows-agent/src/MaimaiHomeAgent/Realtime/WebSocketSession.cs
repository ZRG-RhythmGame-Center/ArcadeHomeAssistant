using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Realtime;

/// <summary>
/// Owns a single client WebSocket connection and exposes safe send / receive primitives.
/// Sends are serialized through a SemaphoreSlim because <see cref="WebSocket"/> only
/// supports one outstanding SendAsync at a time.
/// </summary>
public sealed class WebSocketSession : IAsyncDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    private readonly SemaphoreSlim _sendLock = new(1, 1);
    private readonly ILogger? _logger;

    public WebSocketSession(WebSocket socket, ILogger? logger = null)
    {
        Socket = socket ?? throw new ArgumentNullException(nameof(socket));
        Id = Guid.NewGuid();
        ConnectedAt = DateTimeOffset.UtcNow;
        LastPongAt = ConnectedAt;
        _logger = logger;
    }

    public Guid Id { get; }
    public WebSocket Socket { get; }
    public DateTimeOffset ConnectedAt { get; }
    public DateTimeOffset LastPongAt { get; private set; }

    /// <summary>
    /// Updates the last-pong timestamp. The receive loop calls this whenever any
    /// frame arrives from the client (text, binary, or pong-shaped JSON).
    /// </summary>
    public void MarkPong(DateTimeOffset at) => LastPongAt = at;

    /// <summary>Test seam: bypass MarkPong to simulate an idle client.</summary>
    internal void OverrideLastPongAtForTests(DateTimeOffset at) => LastPongAt = at;

    public async Task SendJsonAsync(EventEnvelope envelope, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        var json = JsonSerializer.Serialize(envelope, JsonOptions);
        var bytes = Encoding.UTF8.GetBytes(json);
        await SendRawAsync(bytes, WebSocketMessageType.Text, ct).ConfigureAwait(false);
    }

    /// <summary>
    /// Sends a small ping payload as a text frame. We deliberately avoid the
    /// raw control-frame ping because <see cref="WebSocket.SendAsync"/> does not
    /// expose ping; clients must respond by echoing or by sending any frame.
    /// </summary>
    public async Task SendPingAsync(CancellationToken ct = default)
    {
        var payload = "{\"type\":\"ping\"}"u8.ToArray();
        await SendRawAsync(payload, WebSocketMessageType.Text, ct).ConfigureAwait(false);
    }

    public async Task CloseAsync(WebSocketCloseStatus status, string? description, CancellationToken ct = default)
    {
        if (Socket.State is WebSocketState.Open or WebSocketState.CloseReceived)
        {
            try
            {
                await Socket.CloseAsync(status, description, ct).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is WebSocketException or OperationCanceledException or ObjectDisposedException)
            {
                _logger?.LogDebug(ex, "WebSocket close failed for session {SessionId}.", Id);
            }
        }
    }

    private async Task SendRawAsync(byte[] payload, WebSocketMessageType type, CancellationToken ct)
    {
        if (Socket.State != WebSocketState.Open) return;

        await _sendLock.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (Socket.State != WebSocketState.Open) return;
            await Socket.SendAsync(payload, type, endOfMessage: true, ct).ConfigureAwait(false);
        }
        finally
        {
            _sendLock.Release();
        }
    }

    /// <summary>
    /// Drains incoming frames so the WebSocket stays alive and we can update
    /// <see cref="LastPongAt"/>. Returns when the peer closes or the cancellation
    /// token fires.
    /// </summary>
    public async Task ReceiveLoopAsync(CancellationToken ct)
    {
        var buffer = new byte[4096];
        try
        {
            while (Socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                WebSocketReceiveResult result;
                try
                {
                    result = await Socket.ReceiveAsync(buffer, ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException) { break; }
                catch (WebSocketException ex)
                {
                    _logger?.LogDebug(ex, "WebSocket receive failed for session {SessionId}.", Id);
                    break;
                }

                if (result.MessageType == WebSocketMessageType.Close)
                {
                    await CloseAsync(WebSocketCloseStatus.NormalClosure, "client closed", ct).ConfigureAwait(false);
                    break;
                }

                // Any frame from the client counts as liveness.
                MarkPong(DateTimeOffset.UtcNow);

                // We do not currently dispatch client commands over WS; drain and continue.
                // result.EndOfMessage may be false; loop continues regardless.
            }
        }
        finally
        {
            _logger?.LogDebug("Receive loop exiting for session {SessionId}.", Id);
        }
    }

    public ValueTask DisposeAsync()
    {
        _sendLock.Dispose();
        Socket.Dispose();
        return ValueTask.CompletedTask;
    }
}
