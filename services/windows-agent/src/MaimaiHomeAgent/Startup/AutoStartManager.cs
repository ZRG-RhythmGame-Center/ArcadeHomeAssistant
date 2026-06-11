using System.Xml.Linq;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Startup;

/// <summary>
/// Manages auto-start integration via Windows Task Scheduler (<c>schtasks.exe</c>).
/// Uses <c>/SC ONLOGON /RL LIMITED</c> so the scheduled task is created at user
/// scope, no admin elevation required.
/// </summary>
public sealed class AutoStartManager : IAutoStartManager
{
    /// <summary>
    /// Scheduled task name. Stable across versions because <see cref="IsEnabledAsync"/>
    /// looks it up by name to detect already-installed installs.
    /// </summary>
    public const string TaskName = "MaimaiHomeAgent";

    private readonly IProcessRunner _runner;
    private readonly ILogger<AutoStartManager> _logger;

    public AutoStartManager(IProcessRunner runner, ILogger<AutoStartManager> logger)
    {
        _runner = runner;
        _logger = logger;
    }

    /// <summary>
    /// Returns true when a scheduled task named <see cref="TaskName"/> exists AND
    /// its &lt;Command&gt; matches the current process executable path
    /// (case-insensitive). Mismatched path → false (a stale task from a previous
    /// install location should be re-enabled by the user).
    /// </summary>
    public async Task<bool> IsEnabledAsync(CancellationToken ct = default)
    {
        var result = await _runner
            .RunAsync("schtasks.exe", $"/Query /TN {TaskName} /XML", ct)
            .ConfigureAwait(false);

        if (result.ExitCode != 0)
        {
            // schtasks exits 1 when the task does not exist. NOT a warning —
            // this is the normal "auto-start disabled" path.
            return false;
        }

        var commandFromTask = ExtractCommandFromXml(result.StandardOutput);
        if (string.IsNullOrWhiteSpace(commandFromTask))
        {
            return false;
        }

        var currentExe = Environment.ProcessPath;
        if (string.IsNullOrWhiteSpace(currentExe))
        {
            return false;
        }

        return string.Equals(
            commandFromTask.Trim().Trim('"'),
            currentExe.Trim(),
            StringComparison.OrdinalIgnoreCase);
    }

    /// <summary>
    /// Creates or replaces (<c>/F</c>) the auto-start task pointing at the
    /// current executable. Returns true on success, false on schtasks failure
    /// (logged as warning, never throws).
    /// </summary>
    public async Task<bool> EnableAsync(CancellationToken ct = default)
    {
        var exe = Environment.ProcessPath
            ?? throw new InvalidOperationException("Environment.ProcessPath is null; cannot enable auto-start.");

        // /TR value must be quoted so that paths containing spaces survive
        // schtasks' tokenization. /SC ONLOGON + /RL LIMITED creates a
        // user-scope task — no admin / UAC prompt.
        var args = $"/Create /TN {TaskName} /SC ONLOGON /TR \"{exe}\" /RL LIMITED /F";

        var result = await _runner.RunAsync("schtasks.exe", args, ct).ConfigureAwait(false);

        if (result.ExitCode == 0)
        {
            _logger.LogInformation("Auto-start task '{TaskName}' enabled for {Exe}.", TaskName, exe);
            return true;
        }

        _logger.LogWarning(
            "schtasks /Create exited with {ExitCode}. stdout={Stdout} stderr={Stderr}",
            result.ExitCode,
            result.StandardOutput.Trim(),
            result.StandardError.Trim());
        return false;
    }

    /// <summary>
    /// Deletes the auto-start task. Returns true on success, false on schtasks
    /// failure (logged as warning, never throws).
    /// </summary>
    public async Task<bool> DisableAsync(CancellationToken ct = default)
    {
        var args = $"/Delete /TN {TaskName} /F";
        var result = await _runner.RunAsync("schtasks.exe", args, ct).ConfigureAwait(false);

        if (result.ExitCode == 0)
        {
            _logger.LogInformation("Auto-start task '{TaskName}' deleted.", TaskName);
            return true;
        }

        _logger.LogWarning(
            "schtasks /Delete exited with {ExitCode}. stdout={Stdout} stderr={Stderr}",
            result.ExitCode,
            result.StandardOutput.Trim(),
            result.StandardError.Trim());
        return false;
    }

    /// <summary>
    /// Parses schtasks /XML output and returns the &lt;Command&gt; element value.
    /// Returns null on parse failure or missing element.
    /// </summary>
    private static string? ExtractCommandFromXml(string xml)
    {
        try
        {
            var doc = XDocument.Parse(xml);
            // Task XML uses the schemas.microsoft.com namespace. Match by
            // local name to avoid hard-coding the prefix.
            var command = doc.Descendants()
                .FirstOrDefault(e => e.Name.LocalName == "Command");
            return command?.Value;
        }
        catch
        {
            return null;
        }
    }
}
