using MaimaiHomeAgent.Files;
using MaimaiHomeAgent.Launcher;
using MaimaiHomeAgent.Power;

namespace MaimaiHomeAgent.Settings;

public sealed record AgentSettingsSnapshot(
    bool AdminPasswordConfigured,
    bool AutoStartEnabled,
    LauncherSettingsDto Launcher,
    IReadOnlyList<FileRootSettingsDto> FileRoots,
    RemoteShutdownSettingsDto RemoteShutdown);

public sealed record AgentSettingsUpdateRequest(
    string? AdminPassword,
    bool? AutoStartEnabled,
    LauncherSettingsDto? Launcher,
    IReadOnlyList<FileRootSettingsDto>? FileRoots,
    RemoteShutdownSettingsDto? RemoteShutdown);

public sealed record LauncherSettingsDto(
    bool ShowOnAgentStart,
    int CanvasWidth,
    int CanvasHeight,
    IReadOnlyList<LauncherItemSettingsDto> Items)
{
    public LauncherOptions ToOptions() => new()
    {
        ShowOnAgentStart = ShowOnAgentStart,
        CanvasWidth = CanvasWidth,
        CanvasHeight = CanvasHeight,
        Items = Items.Select(item => new LauncherItemOptions
        {
            Id = item.Id,
            Name = item.Name,
            CommandLine = item.CommandLine,
            WorkingDirectory = item.WorkingDirectory,
            Key = item.Key,
            Order = item.Order,
            Enabled = item.Enabled
        }).ToList()
    };

    public static LauncherSettingsDto FromOptions(LauncherOptions options) => new(
        options.ShowOnAgentStart,
        options.CanvasWidth,
        options.CanvasHeight,
        options.Items
            .OrderBy(item => item.Order)
            .Select(item => new LauncherItemSettingsDto(
                item.Id ?? string.Empty,
                item.Name ?? string.Empty,
                item.CommandLine ?? string.Empty,
                item.WorkingDirectory,
                item.Key ?? string.Empty,
                item.Order,
                item.Enabled))
            .ToList());
}

public sealed record LauncherItemSettingsDto(
    string Id,
    string Name,
    string CommandLine,
    string? WorkingDirectory,
    string Key,
    int Order,
    bool Enabled);

public sealed record FileRootSettingsDto(string Id, string Name, string Path, bool ReadOnly)
{
    public FileRoot ToFileRoot() => new(Id, Name, Path, ReadOnly);

    public static FileRootSettingsDto FromFileRoot(FileRoot root) => new(root.Id, root.Name, root.Path, root.ReadOnly);
}

public sealed record RemoteShutdownSettingsDto(bool Enabled, string? ControlToken)
{
    public RemoteShutdownOptions ToOptions() => new()
    {
        Enabled = Enabled,
        ControlToken = ControlToken
    };

    public static RemoteShutdownSettingsDto FromOptions(RemoteShutdownOptions options) => new(
        options.Enabled,
        options.ControlToken);
}
