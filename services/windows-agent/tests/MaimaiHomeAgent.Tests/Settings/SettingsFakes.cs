using MaimaiHomeAgent.Startup;

namespace MaimaiHomeAgent.Tests.Settings;

internal sealed class FakeAutoStartManager : IAutoStartManager
{
    public bool Enabled { get; set; }
    public bool NextResult { get; set; } = true;

    public Task<bool> IsEnabledAsync(CancellationToken ct = default)
    {
        return Task.FromResult(Enabled);
    }

    public Task<bool> EnableAsync(CancellationToken ct = default)
    {
        if (NextResult) Enabled = true;

        return Task.FromResult(NextResult);
    }

    public Task<bool> DisableAsync(CancellationToken ct = default)
    {
        if (NextResult) Enabled = false;

        return Task.FromResult(NextResult);
    }
}