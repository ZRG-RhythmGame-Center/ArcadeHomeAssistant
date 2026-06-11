namespace MaimaiHomeAgent.Startup;

public interface IAutoStartManager
{
    Task<bool> IsEnabledAsync(CancellationToken ct = default);

    Task<bool> EnableAsync(CancellationToken ct = default);

    Task<bool> DisableAsync(CancellationToken ct = default);
}
