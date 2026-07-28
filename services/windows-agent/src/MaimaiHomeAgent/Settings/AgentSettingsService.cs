using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using MaimaiHomeAgent.Files;
using MaimaiHomeAgent.Launcher;
using MaimaiHomeAgent.Power;
using MaimaiHomeAgent.Realtime;
using MaimaiHomeAgent.Startup;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Settings;

public sealed class AgentSettingsService : IAgentSettingsService
{
    private const string UserConfigFileName = "appsettings.user.json";
    private readonly IAutoStartManager _autoStartManager;
    private readonly IConfiguration _configuration;
    private readonly EventPublisher? _events;
    private readonly IFileRootService _fileRootService;
    private readonly IOptionsMonitor<LauncherOptions> _launcherOptions;
    private readonly IOptionsMonitor<RemoteShutdownOptions> _remoteShutdownOptions;
    private readonly string _userConfigPath;

    public AgentSettingsService(
        IOptionsMonitor<LauncherOptions> launcherOptions,
        IOptionsMonitor<RemoteShutdownOptions> remoteShutdownOptions,
        IFileRootService fileRootService,
        IAutoStartManager autoStartManager,
        IConfiguration configuration,
        EventPublisher? events = null)
        : this(
            launcherOptions,
            remoteShutdownOptions,
            fileRootService,
            autoStartManager,
            configuration,
            Path.Combine(AppContext.BaseDirectory, UserConfigFileName),
            events)
    {
    }

    internal AgentSettingsService(
        IOptionsMonitor<LauncherOptions> launcherOptions,
        IOptionsMonitor<RemoteShutdownOptions> remoteShutdownOptions,
        IFileRootService fileRootService,
        IAutoStartManager autoStartManager,
        IConfiguration configuration,
        string userConfigPath,
        EventPublisher? events = null)
    {
        _launcherOptions = launcherOptions;
        _remoteShutdownOptions = remoteShutdownOptions;
        _fileRootService = fileRootService;
        _autoStartManager = autoStartManager;
        _configuration = configuration;
        _userConfigPath = userConfigPath;
        _events = events;
    }

    public async Task<AgentSettingsSnapshot> GetAsync(CancellationToken ct = default)
    {
        var autoStartEnabled = await _autoStartManager.IsEnabledAsync(ct).ConfigureAwait(false);
        return new AgentSettingsSnapshot(
            autoStartEnabled,
            LauncherSettingsDto.FromOptions(_launcherOptions.CurrentValue),
            _fileRootService.ListRoots().Select(FileRootSettingsDto.FromFileRoot).ToList(),
            RemoteShutdownSettingsDto.FromOptions(_remoteShutdownOptions.CurrentValue));
    }

    public async Task<SettingsUpdateResult> UpdateAsync(AgentSettingsUpdateRequest request,
        CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(request);

        var errors = Validate(request);
        if (errors.Count > 0) return SettingsUpdateResult.Failed(errors);

        if (request.AutoStartEnabled.HasValue)
        {
            var ok = request.AutoStartEnabled.Value
                ? await _autoStartManager.EnableAsync(ct).ConfigureAwait(false)
                : await _autoStartManager.DisableAsync(ct).ConfigureAwait(false);
            if (!ok)
                return SettingsUpdateResult.Failed(new[]
                {
                    new SettingsValidationError("autostart_update_failed", "开机自启状态更新失败")
                });
        }

        await PersistAsync(request, ct).ConfigureAwait(false);
        ReloadConfiguration();

        if (request.FileRoots is not null) _fileRootService.Reload(request.FileRoots.Select(root => root.ToFileRoot()));

        if (_events is not null) _events.PublishSettingsUpdated(new { updatedAt = DateTimeOffset.UtcNow });

        return SettingsUpdateResult.Ok(await GetAsync(ct).ConfigureAwait(false));
    }

    private static List<SettingsValidationError> Validate(AgentSettingsUpdateRequest request)
    {
        var errors = new List<SettingsValidationError>();
        if (request.Launcher is not null)
            errors.AddRange(LauncherConfigValidator.Validate(request.Launcher.ToOptions())
                .Select(error => new SettingsValidationError(error.Error, error.Message)));

        if (request.FileRoots is not null)
        {
            if (request.FileRoots.Count == 0)
                errors.Add(new SettingsValidationError("file_roots_required", "文件根目录不能为空"));

            var ids = new HashSet<string>(StringComparer.Ordinal);
            foreach (var root in request.FileRoots)
            {
                if (string.IsNullOrWhiteSpace(root.Id))
                    errors.Add(new SettingsValidationError("file_root_id_required", "文件根目录 ID 不能为空"));
                else if (!ids.Add(root.Id))
                    errors.Add(new SettingsValidationError("file_root_id_duplicate", $"文件根目录 ID 重复: {root.Id}"));

                if (string.IsNullOrWhiteSpace(root.Name))
                    errors.Add(new SettingsValidationError("file_root_name_required", $"文件根目录 {root.Id} 名称不能为空"));

                if (string.IsNullOrWhiteSpace(root.Path))
                    errors.Add(new SettingsValidationError("file_root_path_required", $"文件根目录 {root.Id} 路径不能为空"));
                else if (!Directory.Exists(Environment.ExpandEnvironmentVariables(root.Path)))
                    errors.Add(new SettingsValidationError("file_root_path_missing", $"文件根目录路径不存在: {root.Path}"));
            }
        }

        return errors;
    }

    private async Task PersistAsync(AgentSettingsUpdateRequest request, CancellationToken ct)
    {
        if (request.Launcher is null &&
            request.FileRoots is null &&
            request.RemoteShutdown is null)
            return;

        var root = await ReadUserConfigAsync(ct).ConfigureAwait(false);

        if (request.Launcher is not null)
            root["Launcher"] = JsonSerializer.SerializeToNode(request.Launcher.ToOptions(), JsonOptions());

        if (request.FileRoots is not null)
            root["FileRoots"] = JsonSerializer.SerializeToNode(
                request.FileRoots.Select(root => root.ToFileRoot()).ToList(),
                JsonOptions());

        if (request.RemoteShutdown is not null)
            root["RemoteShutdown"] = JsonSerializer.SerializeToNode(request.RemoteShutdown.ToOptions(), JsonOptions());

        Directory.CreateDirectory(Path.GetDirectoryName(_userConfigPath) ?? AppContext.BaseDirectory);
        var tempPath = _userConfigPath + ".tmp";
        var json = root.ToJsonString(JsonOptions());
        await File.WriteAllTextAsync(tempPath, json, ct).ConfigureAwait(false);
        File.Move(tempPath, _userConfigPath, true);
    }

    private async Task<JsonObject> ReadUserConfigAsync(CancellationToken ct)
    {
        if (!File.Exists(_userConfigPath)) return new JsonObject();

        var json = await File.ReadAllTextAsync(_userConfigPath, ct).ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(json)) return new JsonObject();

        return JsonNode.Parse(json)?.AsObject() ?? new JsonObject();
    }

    private void ReloadConfiguration()
    {
        if (_configuration is IConfigurationRoot root) root.Reload();
    }

    private static JsonSerializerOptions JsonOptions()
    {
        return new JsonSerializerOptions
        {
            WriteIndented = true,
            DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
        };
    }
}