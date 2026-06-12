using Avalonia.Controls;
using Avalonia.Threading;
using MaimaiHomeAgent.Settings;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Ui.Avalonia.Settings;

internal sealed class AvaloniaSettingsWindowHost : ISettingsWindowHost
{
    private readonly IAgentSettingsService _settings;
    private readonly IAvaloniaUiThread _uiThread;
    private readonly ILogger<AvaloniaSettingsWindowHost> _logger;
    private SettingsWindow? _window;

    public AvaloniaSettingsWindowHost(
        IAgentSettingsService settings,
        IAvaloniaUiThread uiThread,
        ILogger<AvaloniaSettingsWindowHost> logger)
    {
        _settings = settings;
        _uiThread = uiThread;
        _logger = logger;
    }

    public Task ShowAsync(CancellationToken ct = default) => _uiThread.InvokeAsync(async () =>
    {
        if (_window is not null)
        {
            _window.Activate();
            return;
        }

        var vm = new SettingsWindowViewModel(_settings, _logger);
        await vm.LoadAsync(ct).ConfigureAwait(true);
        _window = new SettingsWindow { DataContext = vm };
        _window.Closed += (_, _) => _window = null;
        _window.Show();
    }, ct);
}
