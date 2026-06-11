namespace MaimaiHomeAgent.Launcher;

public sealed record LauncherStatusDto(
    bool IsVisible,
    bool HasActiveItem,
    string? ActiveItemId,
    string? ActiveItemName,
    string State,
    string? LastError);

public sealed record StartLauncherItemRequest(string? ItemId);

public sealed record LauncherActionResult(bool Accepted, LauncherStatusDto Status, string? Error = null, string? Message = null)
{
    public static LauncherActionResult Ok(LauncherStatusDto status) => new(true, status);

    public static LauncherActionResult Rejected(LauncherStatusDto status, string error, string message) => new(false, status, error, message);
}

public sealed record LauncherItemRuntime(
    string Id,
    string Name,
    string CommandLine,
    string? WorkingDirectory,
    string StopCommandLine,
    string? StopWorkingDirectory,
    string Key,
    int Order);
