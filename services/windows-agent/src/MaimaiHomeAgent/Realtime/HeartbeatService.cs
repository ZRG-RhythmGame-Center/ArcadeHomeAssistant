using System.Net.WebSockets;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Realtime;

/// <summary>
/// Tunables for the WebSocket heartbeat loop. Pulled from configuration via
/// <see cref="IOptions{T}"/> so production and tests can dial them independently.
/// </summary>
public sealed class HeartbeatOptions
{
    public TimeSpan PingInterval { get; set; } = TimeSpan.FromSeconds(30);

    public TimeSpan PongTimeout { get; set; } = TimeSpan.FromSeconds(60);
}

/// <summary>
/// Periodically pings every active session and reaps connections that have not
/// produced any traffic within <see cref="HeartbeatOptions.PongTimeout"/>. Tests
/// can drive the loop directly via <see cref="RunOnceAsync"/>.
/// </summary>
public sealed class HeartbeatService : IHostedService, IDisposable
{
    private readonly EventHub _hub;
    private readonly HeartbeatOptions _options;
    private readonly ILogger<HeartbeatService> _logger;

    private CancellationTokenSource? _cts;
    private Task? _loop;

    public HeartbeatService(EventHub hub, IOptions<HeartbeatOptions> options, ILogger<HeartbeatService> logger)
    {
        _hub = hub ?? throw new ArgumentNullException(nameof(hub));
        _options = options?.Value ?? throw new ArgumentNullException(nameof(options));
        _logger = logger ?? throw new ArgumentNullException(nameof(logger));
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        _loop = Task.Run(() => RunLoopAsync(_cts.Token), CancellationToken.None);
        _logger.LogInformation(
            "Heartbeat service started. PingInterval={Interval} PongTimeout={Timeout}",
            _options.PingInterval, _options.PongTimeout);
        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (_cts is null) return;
        try { _cts.Cancel(); } catch (ObjectDisposedException) { }
        if (_loop is not null)
        {
            try { await _loop.WaitAsync(cancellationToken).ConfigureAwait(false); }
            catch (Exception ex) when (ex is OperationCanceledException or TaskCanceledException) { }
        }
    }

    private async Task RunLoopAsync(CancellationToken ct)
    {
        var interval = _options.PingInterval;
        if (interval <= TimeSpan.Zero) interval = TimeSpan.FromSeconds(30);

        while (!ct.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(interval, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException) { break; }

            try
            {
                await RunOnceAsync(DateTimeOffset.UtcNow, ct).ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Heartbeat tick failed.");
            }
        }
    }

    /// <summary>
    /// Pings every live session and removes any whose <see cref="WebSocketSession.LastPongAt"/>
    /// is older than <see cref="HeartbeatOptions.PongTimeout"/> from <paramref name="now"/>.
    /// </summary>
    public async Task RunOnceAsync(DateTimeOffset now, CancellationToken ct)
    {
        var sessions = _hub.Snapshot();
        if (sessions.Count == 0) return;

        var pingTasks = new List<Task>(sessions.Count);
        var staleIds = new List<Guid>();

        foreach (var session in sessions)
        {
            var idle = now - session.LastPongAt;
            if (idle > _options.PongTimeout)
            {
                staleIds.Add(session.Id);
                _logger.LogInformation(
                    "Heartbeat: session {SessionId} idle for {Idle}; closing.",
                    session.Id, idle);
                continue;
            }
            pingTasks.Add(PingSafelyAsync(session, ct));
        }

        if (pingTasks.Count > 0)
        {
            await Task.WhenAll(pingTasks).ConfigureAwait(false);
        }

        foreach (var id in staleIds)
        {
            await _hub.RemoveAsync(id, ct).ConfigureAwait(false);
        }
    }

    private async Task PingSafelyAsync(WebSocketSession session, CancellationToken ct)
    {
        try
        {
            await session.SendPingAsync(ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is WebSocketException or OperationCanceledException or ObjectDisposedException or InvalidOperationException)
        {
            _logger.LogDebug(ex, "Heartbeat ping failed for session {SessionId}.", session.Id);
        }
    }

    public void Dispose()
    {
        _cts?.Dispose();
    }
}
