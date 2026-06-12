using System.Diagnostics;

namespace MaimaiHomeAgent.Startup;

public interface IElevatedProcessRunner
{
    Task<int> RunAsync(string fileName, string arguments, CancellationToken ct = default);
}

public sealed class ElevatedProcessRunner : IElevatedProcessRunner
{
    public async Task<int> RunAsync(string fileName, string arguments, CancellationToken ct = default)
    {
        var startInfo = new ProcessStartInfo(fileName, arguments)
        {
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden
        };

        using var process = Process.Start(startInfo)
                            ?? throw new InvalidOperationException($"Failed to start elevated process: {fileName}");
        await process.WaitForExitAsync(ct).ConfigureAwait(false);
        return process.ExitCode;
    }
}