using MaimaiHomeAgent.Realtime;
using MaimaiHomeAgent.Startup;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Launcher;

public sealed class LauncherService : ILauncherService, IHostedService
{
    private readonly IOptionsMonitor<LauncherOptions> _options;
    private readonly IProcessRunner _processRunner;
    private readonly ILauncherWindowHost _window;
    private readonly EventPublisher _events;
    private readonly ILogger<LauncherService> _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private LauncherItemRuntime? _activeItem;
    private string _state = "idle";
    private string? _lastError;

    public LauncherService(
        IOptionsMonitor<LauncherOptions> options,
        IProcessRunner processRunner,
        ILauncherWindowHost window,
        EventPublisher events,
        ILogger<LauncherService> logger)
    {
        _options = options;
        _processRunner = processRunner;
        _window = window;
        _events = events;
        _logger = logger;
    }

    public async Task StartAsync(CancellationToken cancellationToken)
    {
        if (_options.CurrentValue.ShowOnAgentStart)
        {
            await ShowAsync(cancellationToken).ConfigureAwait(false);
        }
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    public LauncherStatusDto GetStatus() => CreateStatus();

    internal LauncherOptions GetCurrentOptions() => _options.CurrentValue;

    public async Task<LauncherActionResult> ShowAsync(CancellationToken ct = default)
    {
        var items = GetEnabledItems();
        var options = _options.CurrentValue;
        var navigation = new LauncherNavigationOptions(
            options.NavigateLeftKey ?? LauncherNavigationOptions.Default.NavigateLeftKey,
            options.NavigateRightKey ?? LauncherNavigationOptions.Default.NavigateRightKey,
            options.ConfirmKey ?? LauncherNavigationOptions.Default.ConfirmKey);
        await _window.ShowAsync(items, navigation, StartItemAsync, ct).ConfigureAwait(false);
        _events.PublishLauncherEvent(EventTypes.LauncherShown, new { shownAt = DateTimeOffset.UtcNow });
        return LauncherActionResult.Ok(CreateStatus());
    }

    public async Task<LauncherActionResult> HideAsync(CancellationToken ct = default)
    {
        await _window.HideAsync(ct).ConfigureAwait(false);
        _events.PublishLauncherEvent(EventTypes.LauncherHidden, new { hiddenAt = DateTimeOffset.UtcNow });
        return LauncherActionResult.Ok(CreateStatus());
    }

    public async Task<LauncherActionResult> StartItemAsync(string itemId, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(itemId))
        {
            return LauncherActionResult.Rejected(CreateStatus(), "launcher_item_id_required", "启动项 ID 不能为空");
        }

        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (_activeItem is not null)
            {
                return LauncherActionResult.Rejected(CreateStatus(), "launcher_item_already_active", "已有启动项正在运行");
            }

            var item = GetEnabledItems().FirstOrDefault(candidate => string.Equals(candidate.Id, itemId, StringComparison.Ordinal));
            if (item is null)
            {
                return LauncherActionResult.Rejected(CreateStatus(), "launcher_item_not_found", "启动项不存在或未启用");
            }

            _state = "starting";
            _lastError = null;
            _events.PublishLauncherEvent(EventTypes.LauncherItemStarted, new { item.Id, item.Name, startedAt = DateTimeOffset.UtcNow });

            var result = await RunCommandAsync(item.CommandLine, item.WorkingDirectory, ct).ConfigureAwait(false);
            if (result.ExitCode != 0)
            {
                _state = "failed";
                _lastError = BuildProcessError("启动命令执行失败", result);
                _events.PublishLauncherEvent(EventTypes.LauncherItemFailed, new { item.Id, item.Name, error = _lastError, failedAt = DateTimeOffset.UtcNow });
                return LauncherActionResult.Rejected(CreateStatus(), "launcher_item_start_failed", _lastError);
            }

            _activeItem = item;
            _state = "running";
            await _window.MinimizeAsync(ct).ConfigureAwait(false);
            _events.PublishLauncherEvent(EventTypes.LauncherMinimized, new { item.Id, item.Name, minimizedAt = DateTimeOffset.UtcNow });
            return LauncherActionResult.Ok(CreateStatus());
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _state = "failed";
            _lastError = ex.Message;
            _logger.LogError(ex, "Launcher item start failed.");
            _events.PublishLauncherEvent(EventTypes.LauncherItemFailed, new { itemId, error = _lastError, failedAt = DateTimeOffset.UtcNow });
            return LauncherActionResult.Rejected(CreateStatus(), "launcher_item_start_failed", _lastError);
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task<LauncherActionResult> StopActiveItemAsync(CancellationToken ct = default)
    {
        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (_activeItem is null)
            {
                return LauncherActionResult.Rejected(CreateStatus(), "launcher_item_not_active", "当前没有正在运行的启动项");
            }

            var item = _activeItem;
            _state = "stopping";
            _lastError = null;
            _events.PublishLauncherEvent(EventTypes.LauncherItemStopStarted, new { item.Id, item.Name, startedAt = DateTimeOffset.UtcNow });

            var workingDirectory = string.IsNullOrWhiteSpace(item.StopWorkingDirectory)
                ? item.WorkingDirectory
                : item.StopWorkingDirectory;
            var result = await RunCommandAsync(item.StopCommandLine, workingDirectory, ct).ConfigureAwait(false);
            if (result.ExitCode != 0)
            {
                _state = "stop_failed";
                _lastError = BuildProcessError("关闭命令执行失败", result);
                _events.PublishLauncherEvent(EventTypes.LauncherItemStopFailed, new { item.Id, item.Name, error = _lastError, failedAt = DateTimeOffset.UtcNow });
                return LauncherActionResult.Rejected(CreateStatus(), "launcher_item_stop_failed", _lastError);
            }

            _activeItem = null;
            _state = "idle";
            _events.PublishLauncherEvent(EventTypes.LauncherItemStopCompleted, new { item.Id, item.Name, completedAt = DateTimeOffset.UtcNow });
            await ShowAsync(ct).ConfigureAwait(false);
            return LauncherActionResult.Ok(CreateStatus());
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _state = "stop_failed";
            _lastError = ex.Message;
            _logger.LogError(ex, "Launcher item stop failed.");
            _events.PublishLauncherEvent(EventTypes.LauncherItemStopFailed, new { error = _lastError, failedAt = DateTimeOffset.UtcNow });
            return LauncherActionResult.Rejected(CreateStatus(), "launcher_item_stop_failed", _lastError);
        }
        finally
        {
            _gate.Release();
        }
    }

    private IReadOnlyList<LauncherItemRuntime> GetEnabledItems() => _options.CurrentValue.Items
        .Where(item => item.Enabled)
        .OrderBy(item => item.Order)
        .Select(item => new LauncherItemRuntime(
            item.Id ?? string.Empty,
            item.Name ?? string.Empty,
            item.Title ?? string.Empty,
            item.Note,
            item.IconPath,
            item.CommandLine ?? string.Empty,
            item.WorkingDirectory,
            item.StopCommandLine ?? string.Empty,
            item.StopWorkingDirectory,
            item.Key ?? string.Empty,
            item.Order))
        .ToList();

    private Task<ProcessResult> RunCommandAsync(string commandLine, string? workingDirectory, CancellationToken ct)
    {
        var command = string.IsNullOrWhiteSpace(workingDirectory)
            ? $"/C {commandLine}"
            : $"/C cd /D \"{workingDirectory}\" && {commandLine}";
        return _processRunner.RunAsync("cmd.exe", command, ct);
    }

    private LauncherStatusDto CreateStatus() => new(
        IsVisible: _window.IsVisible,
        HasActiveItem: _activeItem is not null,
        ActiveItemId: _activeItem?.Id,
        ActiveItemName: _activeItem?.Name,
        State: _state,
        LastError: _lastError);

    private static string BuildProcessError(string prefix, ProcessResult result)
    {
        var detail = string.IsNullOrWhiteSpace(result.StandardError)
            ? result.StandardOutput.Trim()
            : result.StandardError.Trim();
        return string.IsNullOrWhiteSpace(detail)
            ? $"{prefix}，退出码 {result.ExitCode}"
            : $"{prefix}，退出码 {result.ExitCode}: {detail}";
    }
}
