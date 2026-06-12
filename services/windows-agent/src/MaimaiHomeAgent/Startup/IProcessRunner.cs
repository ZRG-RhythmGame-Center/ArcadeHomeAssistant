using System.Diagnostics;
using System.Text;

namespace MaimaiHomeAgent.Startup;

/// <summary>
///     Result of running an external process. Captures exit code + stdout/stderr.
/// </summary>
public sealed record ProcessResult(int ExitCode, string StandardOutput, string StandardError);

/// <summary>
///     Abstraction over <see cref="System.Diagnostics.Process" /> for testability.
/// </summary>
public interface IProcessRunner
{
    Task<ProcessResult> RunAsync(string fileName, string arguments, CancellationToken ct = default);
}

/// <summary>
///     Default <see cref="IProcessRunner" /> using <see cref="System.Diagnostics.Process" />.
/// </summary>
public sealed class ProcessRunner : IProcessRunner
{
    public async Task<ProcessResult> RunAsync(string fileName, string arguments, CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo(fileName, arguments)
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };

        using var process = new Process { StartInfo = psi };
        process.Start();

        var stdoutTask = process.StandardOutput.ReadToEndAsync(CancellationToken.None);
        var stderrTask = process.StandardError.ReadToEndAsync(CancellationToken.None);

        try
        {
            await process.WaitForExitAsync(ct).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            // Kill the process so the ReadToEnd tasks complete.
            try
            {
                process.Kill(true);
            }
            catch
            {
                /* best-effort */
            }

            await Task.WhenAll(stdoutTask, stderrTask).ConfigureAwait(false);
            throw;
        }

        var stdout = await stdoutTask.ConfigureAwait(false);
        var stderr = await stderrTask.ConfigureAwait(false);

        return new ProcessResult(process.ExitCode, stdout, stderr);
    }
}