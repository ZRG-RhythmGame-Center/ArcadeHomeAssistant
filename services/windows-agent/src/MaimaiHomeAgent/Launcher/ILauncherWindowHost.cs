namespace MaimaiHomeAgent.Launcher;

public interface ILauncherWindowHost
{
    bool IsVisible { get; }

    Task ShowAsync(IReadOnlyList<LauncherItemRuntime> items, Func<string, CancellationToken, Task> onKeySelected, CancellationToken ct = default);

    Task MinimizeAsync(CancellationToken ct = default);
}
