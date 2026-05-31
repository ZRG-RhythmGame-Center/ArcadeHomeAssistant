using MaimaiHomeAgent.Startup;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Tray;

/// <summary>
/// System-tray hosted service. Creates a Win32 tray icon with EXACTLY 3 menu
/// items: status (disabled), auto-start toggle, exit. Authentication has been
/// removed (LAN-only deployment), so there is no pairing-code menu entry.
///
/// The Win32 specifics are isolated behind <see cref="ITrayIconHost"/> and
/// <see cref="IUiThreadPump"/> so the class is testable without a real
/// Windows message pump.
/// </summary>
public sealed class TrayApp : IHostedService, IAsyncDisposable
{
    private readonly AutoStartManager _autoStart;
    private readonly IHostApplicationLifetime _lifetime;
    private readonly ILogger<TrayApp> _logger;
    private ITrayIconHost _host;
    private readonly IUiThreadPump _pump;

    /// <summary>
    /// Production constructor — creates real Win32 implementations.
    /// </summary>
    public TrayApp(
        AutoStartManager autoStart,
        IHostApplicationLifetime lifetime,
        ILogger<TrayApp> logger)
    {
        _autoStart = autoStart;
        _lifetime = lifetime;
        _logger = logger;
        _pump = new WindowsFormsPump();
        _host = new Win32TrayIconHost(
            getAutoStartEnabled: SafeIsAutoStartEnabled,
            onToggleAutoStart: OnToggleAutoStartAsync,
            onExit: OnExit);
    }

    /// <summary>
    /// Seam constructor for tests — accepts injected implementations.
    /// </summary>
    internal TrayApp(
        AutoStartManager autoStart,
        IHostApplicationLifetime lifetime,
        ILogger<TrayApp> logger,
        ITrayIconHost host,
        IUiThreadPump pump)
    {
        _autoStart = autoStart;
        _lifetime = lifetime;
        _logger = logger;
        _host = host;
        _pump = pump;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _pump.Start(() =>
        {
            _host.Create();
        });

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _pump.Stop();

        try { _host.Dispose(); }
        catch { /* best-effort */ }

        return Task.CompletedTask;
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync(CancellationToken.None).ConfigureAwait(false);
    }

    // ------------------------------------------------------------------ //
    //  Internal seams for characterization tests                          //
    // ------------------------------------------------------------------ //

    /// <summary>Test seam: directly invoke the exit handler.</summary>
    internal void SimulateExit() => OnExit();

    /// <summary>Test seam: directly invoke the auto-start toggle handler.</summary>
    internal Task SimulateToggleAutoStartAsync() => OnToggleAutoStartAsync();

    // ------------------------------------------------------------------ //
    //  Private handlers                                                   //
    // ------------------------------------------------------------------ //

    private async Task OnToggleAutoStartAsync()
    {
        try
        {
            var nowEnabled = await _autoStart.IsEnabledAsync().ConfigureAwait(false);
            bool ok;
            if (nowEnabled)
            {
                ok = await _autoStart.DisableAsync().ConfigureAwait(false);
            }
            else
            {
                ok = await _autoStart.EnableAsync().ConfigureAwait(false);
            }

            var afterToggle = await _autoStart.IsEnabledAsync().ConfigureAwait(false);
            _host.UpdateAutoStartChecked(afterToggle);

            if (!ok)
            {
                _logger.LogWarning(
                    "Tray: schtasks operation failed; auto-start state remains {Enabled}.",
                    afterToggle);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Tray: auto-start toggle failed.");
        }
    }

    private void OnExit()
    {
        _lifetime.StopApplication();
    }

    private bool SafeIsAutoStartEnabled()
    {
        try
        {
            return _autoStart
                .IsEnabledAsync()
                .Wait(TimeSpan.FromSeconds(2))
                && _autoStart.IsEnabledAsync().GetAwaiter().GetResult();
        }
        catch
        {
            return false;
        }
    }
}

internal static class TaskExtensions
{
    public static bool Wait(this Task task, TimeSpan timeout)
    {
        try { return task.Wait(timeout); }
        catch { return false; }
    }
}
