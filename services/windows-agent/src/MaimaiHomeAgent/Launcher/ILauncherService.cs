namespace MaimaiHomeAgent.Launcher;

public interface ILauncherService
{
    LauncherStatusDto GetStatus();

    Task<LauncherActionResult> ShowAsync(CancellationToken ct = default);

    Task<LauncherActionResult> HideAsync(CancellationToken ct = default);

    Task<LauncherActionResult> StartItemAsync(string itemId, CancellationToken ct = default);

    Task<LauncherActionResult> StopActiveItemAsync(CancellationToken ct = default);
}
