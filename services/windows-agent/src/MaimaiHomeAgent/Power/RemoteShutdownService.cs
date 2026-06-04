using MaimaiHomeAgent.Realtime;
using Microsoft.Extensions.Logging;
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
    private readonly object _gate = new();
    private readonly IOptionsMonitor<RemoteShutdownOptions> _options;
    private readonly IRemoteShutdownExecutor _executor;
    private readonly EventPublisher _events;
    private readonly ILogger<RemoteShutdownService> _logger;

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
            return options.Enabled &&
                _executor.IsSupported &&
                !string.IsNullOrWhiteSpace(options.ControlToken);
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
        {
            return new ExecuteShutdownResult(
                Accepted: false,
                Conflict: false,
                Status: GetStatus(),
                Error: "remote_shutdown_unavailable");
        }

        var executedAt = DateTimeOffset.UtcNow;
        lock (_gate)
        {
            if (_isExecuting)
            {
                return new ExecuteShutdownResult(
                    Accepted: false,
                    Conflict: true,
                    Status: BuildStatusLocked(),
                    Error: "shutdown_already_executing");
            }

            _isExecuting = true;
            _lastError = null;
        }

        _logger.LogWarning("Remote shutdown executing immediately. RequestedBy={RequestedBy}", requestedBy);
        Publish(EventTypes.PowerShutdownExecuting, executedAt, error: null);

        try
        {
            // Do not tie shutdown.exe to the HTTP request cancellation token.
            // Once an authorized user confirms shutdown, disconnecting the
            // client should not cancel the OS-level operation.
            await _executor.ExecuteShutdownAsync(CancellationToken.None).ConfigureAwait(false);

            var executingStatus = new RemoteShutdownStatusDto(
                Available: IsAvailable,
                State: "executing",
                Error: null);

            return new ExecuteShutdownResult(
                Accepted: true,
                Conflict: false,
                Status: executingStatus);
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
                Accepted: false,
                Conflict: false,
                Status: GetStatus(),
                Error: "shutdown_failed");
        }
        finally
        {
            lock (_gate)
            {
                if (_lastError is null)
                {
                    // If shutdown.exe succeeds the machine is expected to go down.
                    // Clear the transient flag so tests and blocked OS policies do
                    // not leave the local process permanently "executing".
                    _isExecuting = false;
                }
            }
        }
    }

    private RemoteShutdownStatusDto BuildStatusLocked()
    {
        if (_isExecuting)
        {
            return new RemoteShutdownStatusDto(
                Available: IsAvailable,
                State: "executing",
                Error: null);
        }

        return new RemoteShutdownStatusDto(
            Available: IsAvailable,
            State: _lastError is null ? "idle" : "failed",
            Error: _lastError);
    }

    private void Publish(string eventType, DateTimeOffset executedAt, string? error)
    {
        _events.PublishRemoteShutdownEvent(eventType, new RemoteShutdownEventDto(
            State: eventType switch
            {
                EventTypes.PowerShutdownExecuting => "executing",
                EventTypes.PowerShutdownFailed => "failed",
                _ => "unknown",
            },
            ExecutedAt: executedAt,
            Error: error));
    }
}
