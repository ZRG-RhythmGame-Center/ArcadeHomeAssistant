using System.Collections.Concurrent;
using System.Net.WebSockets;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Realtime;

/// <summary>
/// Process-wide registry of active WebSocket sessions plus broadcast fan-out.
/// Registered as a singleton; lifetime is the host. Add/Remove are thread-safe;
/// broadcast sends concurrently to all sessions and best-effort drops failures.
/// </summary>
public class EventHub
{
    private readonly ConcurrentDictionary<Guid, WebSocketSession> _sessions = new();
    private readonly ILogger<EventHub> _logger;

    public EventHub(ILogger<EventHub> logger)
    {
        _logger = logger ?? throw new ArgumentNullException(nameof(logger));
    }

    public int SessionCount => _sessions.Count;

    /// <summary>
    /// Snapshot of currently registered sessions. Useful for the heartbeat scan
    /// without holding a lock.
    /// </summary>
    public IReadOnlyCollection<WebSocketSession> Snapshot() => _sessions.Values.ToArray();

    /// <summary>
    /// Registers a freshly-accepted WebSocket and runs its receive loop until it
    /// closes. The caller (the /api/events handler) must keep awaiting this so
    /// ASP.NET Core does not tear down the connection.
    /// </summary>
    public async Task AddAsync(WebSocket socket, CancellationToken ct)
    {
        ArgumentNullException.ThrowIfNull(socket);
        var session = new WebSocketSession(socket, _logger);
        _sessions[session.Id] = session;
        _logger.LogInformation("WebSocket session {SessionId} connected. TotalSessions={Total}", session.Id, _sessions.Count);

        try
        {
            await session.ReceiveLoopAsync(ct).ConfigureAwait(false);
        }
        finally
        {
            await RemoveAsync(session.Id, CancellationToken.None).ConfigureAwait(false);
        }
    }

    public async Task RemoveAsync(Guid id, CancellationToken ct)
    {
        if (_sessions.TryRemove(id, out var session))
        {
            _logger.LogInformation("WebSocket session {SessionId} removed. RemainingSessions={Total}", id, _sessions.Count);
            await session.CloseAsync(WebSocketCloseStatus.NormalClosure, "removed", ct).ConfigureAwait(false);
            await session.DisposeAsync().ConfigureAwait(false);
        }
    }

    /// <summary>
    /// Fans the envelope out to every live session. A failure on one session
    /// does not poison the broadcast for the rest; the failing session will be
    /// reaped by the heartbeat or by its own receive loop.
    /// </summary>
    public virtual async Task BroadcastAsync(EventEnvelope envelope, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        if (_sessions.IsEmpty) return;

        var sessions = _sessions.Values.ToArray();
        var sendTasks = new List<Task>(sessions.Length);
        foreach (var session in sessions)
        {
            sendTasks.Add(SendSafelyAsync(session, envelope, ct));
        }
        await Task.WhenAll(sendTasks).ConfigureAwait(false);
    }

    private async Task SendSafelyAsync(WebSocketSession session, EventEnvelope envelope, CancellationToken ct)
    {
        try
        {
            await session.SendJsonAsync(envelope, ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is WebSocketException or OperationCanceledException or ObjectDisposedException or InvalidOperationException)
        {
            _logger.LogDebug(ex, "Broadcast send failed for session {SessionId}; will be reaped.", session.Id);
        }
    }
}
