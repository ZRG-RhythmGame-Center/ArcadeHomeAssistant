using MaimaiHomeAgent.Startup;

namespace MaimaiHomeAgent.Power;

public interface IRemoteShutdownExecutor
{
    bool IsSupported { get; }

    Task ExecuteShutdownAsync(CancellationToken ct = default);
}

public sealed class RemoteShutdownExecutionException : Exception
{
    public RemoteShutdownExecutionException(string message) : base(message)
    {
    }
}

public sealed class WindowsRemoteShutdownExecutor : IRemoteShutdownExecutor
{
    private readonly IProcessRunner _runner;

    public WindowsRemoteShutdownExecutor(IProcessRunner runner)
    {
        _runner = runner;
    }

    public bool IsSupported => OperatingSystem.IsWindows();

    public async Task ExecuteShutdownAsync(CancellationToken ct = default)
    {
        if (!IsSupported) throw new RemoteShutdownExecutionException("Remote shutdown is only supported on Windows.");

        var result = await _runner.RunAsync("shutdown.exe", "/s /t 0", ct).ConfigureAwait(false);
        if (result.ExitCode == 0) return;

        var detail = string.IsNullOrWhiteSpace(result.StandardError)
            ? result.StandardOutput.Trim()
            : result.StandardError.Trim();
        if (string.IsNullOrWhiteSpace(detail)) detail = $"shutdown.exe exited with code {result.ExitCode}";

        throw new RemoteShutdownExecutionException(detail);
    }
}