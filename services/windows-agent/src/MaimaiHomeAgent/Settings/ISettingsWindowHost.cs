namespace MaimaiHomeAgent.Settings;

public interface ISettingsWindowHost
{
    Task ShowAsync(CancellationToken ct = default);
}
