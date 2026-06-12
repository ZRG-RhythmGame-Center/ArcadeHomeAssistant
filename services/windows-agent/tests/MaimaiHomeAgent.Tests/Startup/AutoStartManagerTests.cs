using MaimaiHomeAgent.Startup;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Abstractions;

namespace MaimaiHomeAgent.Tests.Startup;

public class AutoStartManagerTests
{
    private const string TaskName = "MaimaiHomeAgent";

    [Fact]
    public async Task EnableAsync_PassesCorrectCreateArgs()
    {
        var runner = new RecordingProcessRunner(new ProcessResult(0, string.Empty, string.Empty));
        var manager = new AutoStartManager(runner, NullLogger<AutoStartManager>.Instance);

        var ok = await manager.EnableAsync();

        Assert.True(ok);
        var call = Assert.Single(runner.Calls);
        Assert.Equal("schtasks.exe", call.FileName);
        Assert.Contains("/Create", call.Arguments);
        Assert.Contains($"/TN {TaskName}", call.Arguments);
        Assert.Contains("/SC ONLOGON", call.Arguments);
        Assert.Contains("/RL LIMITED", call.Arguments);
        Assert.Contains("/F", call.Arguments);
        // Exec path must be quoted in the args
        Assert.Contains("/TR \"", call.Arguments);
    }

    [Fact]
    public async Task DisableAsync_PassesCorrectDeleteArgs()
    {
        var runner = new RecordingProcessRunner(new ProcessResult(0, string.Empty, string.Empty));
        var manager = new AutoStartManager(runner, NullLogger<AutoStartManager>.Instance);

        var ok = await manager.DisableAsync();

        Assert.True(ok);
        var call = Assert.Single(runner.Calls);
        Assert.Equal("schtasks.exe", call.FileName);
        Assert.Contains("/Delete", call.Arguments);
        Assert.Contains($"/TN {TaskName}", call.Arguments);
        Assert.Contains("/F", call.Arguments);
    }

    [Fact]
    public async Task IsEnabledAsync_TaskExistsAndPathMatches_ReturnsTrue()
    {
        var currentExe = Environment.ProcessPath ?? "fallback.exe";
        var xml = BuildTaskXml(currentExe);
        var runner = new RecordingProcessRunner(new ProcessResult(0, xml, string.Empty));
        var manager = new AutoStartManager(runner, NullLogger<AutoStartManager>.Instance);

        var enabled = await manager.IsEnabledAsync();

        Assert.True(enabled);
        var call = Assert.Single(runner.Calls);
        Assert.Contains("/Query", call.Arguments);
        Assert.Contains($"/TN {TaskName}", call.Arguments);
        Assert.Contains("/XML", call.Arguments);
    }

    [Fact]
    public async Task IsEnabledAsync_PathMismatch_ReturnsFalse()
    {
        var xml = BuildTaskXml(@"C:\OtherProcess\different.exe");
        var runner = new RecordingProcessRunner(new ProcessResult(0, xml, string.Empty));
        var manager = new AutoStartManager(runner, NullLogger<AutoStartManager>.Instance);

        var enabled = await manager.IsEnabledAsync();

        Assert.False(enabled);
    }

    [Fact]
    public async Task IsEnabledAsync_TaskNotFound_ReturnsFalse()
    {
        // schtasks exits 1 when task does not exist
        var runner = new RecordingProcessRunner(new ProcessResult(
            1,
            string.Empty,
            "ERROR: The system cannot find the file specified."));
        var manager = new AutoStartManager(runner, NullLogger<AutoStartManager>.Instance);

        var enabled = await manager.IsEnabledAsync();

        Assert.False(enabled);
    }

    [Fact]
    public async Task EnableAsync_NonZeroExit_LogsWarningAndReturnsFalse()
    {
        var logger = new RecordingLogger<AutoStartManager>();
        var runner = new RecordingProcessRunner(new ProcessResult(
            1,
            string.Empty,
            "ERROR: Access is denied."));
        var manager = new AutoStartManager(runner, logger);

        var ok = await manager.EnableAsync();

        Assert.False(ok);
        Assert.Contains(logger.Records, r => r.Level == LogLevel.Warning);
    }

    [Fact]
    public async Task DisableAsync_NonZeroExit_LogsWarningAndReturnsFalse()
    {
        var logger = new RecordingLogger<AutoStartManager>();
        var runner = new RecordingProcessRunner(new ProcessResult(
            1,
            string.Empty,
            "ERROR: The system cannot find the file specified."));
        var manager = new AutoStartManager(runner, logger);

        var ok = await manager.DisableAsync();

        Assert.False(ok);
        Assert.Contains(logger.Records, r => r.Level == LogLevel.Warning);
    }

    private static string BuildTaskXml(string command)
    {
        return $"""
                <?xml version="1.0" encoding="UTF-16"?>
                <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
                  <Triggers>
                    <LogonTrigger>
                      <Enabled>true</Enabled>
                    </LogonTrigger>
                  </Triggers>
                  <Actions Context="Author">
                    <Exec>
                      <Command>{command}</Command>
                    </Exec>
                  </Actions>
                </Task>
                """;
    }

    private sealed class RecordingProcessRunner : IProcessRunner
    {
        private readonly ProcessResult _result;

        public RecordingProcessRunner(ProcessResult result)
        {
            _result = result;
        }

        public List<(string FileName, string Arguments)> Calls { get; } = new();

        public Task<ProcessResult> RunAsync(string fileName, string arguments, CancellationToken ct = default)
        {
            Calls.Add((fileName, arguments));
            return Task.FromResult(_result);
        }
    }

    private sealed class RecordingLogger<T> : ILogger<T>
    {
        public List<Record> Records { get; } = new();

        public IDisposable? BeginScope<TState>(TState state) where TState : notnull
        {
            return null;
        }

        public bool IsEnabled(LogLevel logLevel)
        {
            return true;
        }

        public void Log<TState>(
            LogLevel logLevel,
            EventId eventId,
            TState state,
            Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            Records.Add(new Record(logLevel, formatter(state, exception), exception));
        }

        public record Record(LogLevel Level, string Message, Exception? Exception);
    }
}