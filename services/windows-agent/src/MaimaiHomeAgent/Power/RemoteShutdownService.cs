using MaimaiHomeAgent.Realtime;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Power;

public interface IRemoteShutdownService
{
    bool IsAvailable { get; }

    RemoteShutdownStatusDto GetStatus();

    Task<ExecuteShutdownResult> ExecuteAsync(string requestedBy, CancellationToken ct = default);
}

public sealed class RemoteShutdownService : IRemoteShutdownService
{
    private readonly EventPublisher _events;
    private readonly IRemoteShutdownExecutor _executor;
    private readonly object _gate = new();
    private readonly ILogger<RemoteShutdownService> _logger;
    private readonly IOptionsMonitor<RemoteShutdownOptions> _options;

    private bool _isExecuting;
    private string? _lastError;

    public RemoteShutdownService(
        IOptionsMonitor<RemoteShutdownOptions> options,
        IRemoteShutdownExecutor executor,
        EventPublisher events,
        ILogger<RemoteShutdownService> logger)
    {
        _options = options;
        _executor = executor;
        _events = events;
        _logger = logger;
    }

    public bool IsAvailable
    {
        get
        {
            var options = _options.CurrentValue;
            return options.Enabled && _executor.IsSupported;
        }
    }

    public RemoteShutdownStatusDto GetStatus()
    {
        lock (_gate)
        {
            return BuildStatusLocked();
        }
    }

    public async Task<ExecuteShutdownResult> ExecuteAsync(string requestedBy, CancellationToken ct = default)
    {
        if (!IsAvailable)
            return new ExecuteShutdownResult(
                false,
                false,
                GetStatus(),
                "remote_shutdown_unavailable");

        var executedAt = DateTimeOffset.UtcNow;
        lock (_gate)
        {
            if (_isExecuting)
                return new ExecuteShutdownResult(
                    false,
                    true,
                    BuildStatusLocked(),
                    "shutdown_already_executing");

            _isExecuting = true;
            _lastError = null;
        }

        _logger.LogWarning("Remote shutdown executing immediately. RequestedBy={RequestedBy}", requestedBy);
        Publish(EventTypes.PowerShutdownExecuting, executedAt, null);

        try
        {
            // Do not tie shutdown.exe to the HTTP request cancellation token.
            // Once an authorized user confirms shutdown, disconnecting the
            // client should not cancel the OS-level operation.
            await _executor.ExecuteShutdownAsync(CancellationToken.None).ConfigureAwait(false);

            var executingStatus = new RemoteShutdownStatusDto(
                IsAvailable,
                "executing",
                null);

            return new ExecuteShutdownResult(
                true,
                false,
                executingStatus);
        }
        catch (Exception ex)
        {
            lock (_gate)
            {
                _lastError = ex.Message;
                _isExecuting = false;
            }

            _logger.LogError(ex, "Remote shutdown failed.");
            Publish(EventTypes.PowerShutdownFailed, executedAt, ex.Message);

            return new ExecuteShutdownResult(
                false,
                false,
                GetStatus(),
                "shutdown_failed");
        }
        finally
        {
            lock (_gate)
            {
                if (_lastError is null)
                    // If shutdown.exe succeeds the machine is expected to go down.
                    // Clear the transient flag so tests and blocked OS policies do
                    // not leave the local process permanently "executing".
                    _isExecuting = false;
            }
        }
    }

    private RemoteShutdownStatusDto BuildStatusLocked()
    {
        if (_isExecuting)
            return new RemoteShutdownStatusDto(
                IsAvailable,
                "executing",
                null);

        return new RemoteShutdownStatusDto(
            IsAvailable,
            _lastError is null ? "idle" : "failed",
            _lastError);
    }

    private void Publish(string eventType, DateTimeOffset executedAt, string? error)
    {
        _events.PublishRemoteShutdownEvent(eventType, new RemoteShutdownEventDto(
            eventType switch
            {
                EventTypes.PowerShutdownExecuting => "executing",
                EventTypes.PowerShutdownFailed => "failed",
                _ => "unknown"
            },
            executedAt,
            error));
    }
}