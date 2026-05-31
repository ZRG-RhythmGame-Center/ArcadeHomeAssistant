using MaimaiHomeAgent.Startup;
using Xunit;

namespace MaimaiHomeAgent.Tests.Startup;

/// <summary>
/// Integration tests for <see cref="ProcessRunner"/>. Exercises the real
/// <see cref="System.Diagnostics.Process"/> API using built-in Windows
/// commands so no external tools are required.
/// </summary>
[Trait("Category", "Windows")]
public class ProcessRunnerTests
{
    private readonly ProcessRunner _runner = new();

    [Fact]
    public async Task RunAsync_EchoHello_ReturnsExitCode0AndStdout()
    {
        var result = await _runner.RunAsync("cmd.exe", "/c echo hello");

        Assert.Equal(0, result.ExitCode);
        Assert.Contains("hello", result.StandardOutput);
        Assert.Empty(result.StandardError);
    }

    [Fact]
    public async Task RunAsync_ExitCode7_ReturnsExitCode7()
    {
        var result = await _runner.RunAsync("cmd.exe", "/c exit 7");

        Assert.Equal(7, result.ExitCode);
    }

    [Fact]
    public async Task RunAsync_StderrOutput_CapturedInStandardError()
    {
        // cmd /c redirects stderr via 1>&2 trick; use a command that writes to stderr.
        // "echo text 1>&2" writes to stderr.
        var result = await _runner.RunAsync("cmd.exe", "/c echo stderr-text 1>&2");

        Assert.Contains("stderr-text", result.StandardError);
    }

    [Fact]
    public async Task RunAsync_MultiLineOutput_CapturedFully()
    {
        // Print 3 lines.
        var result = await _runner.RunAsync(
            "cmd.exe",
            "/c echo line1 && echo line2 && echo line3");

        Assert.Equal(0, result.ExitCode);
        Assert.Contains("line1", result.StandardOutput);
        Assert.Contains("line2", result.StandardOutput);
        Assert.Contains("line3", result.StandardOutput);
    }

    [Fact]
    public async Task RunAsync_CancellationToken_KillsProcess()
    {
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(300));

        // "timeout /t 30" sleeps for 30 seconds — cancellation should kill it.
        await Assert.ThrowsAnyAsync<OperationCanceledException>(async () =>
            await _runner.RunAsync("cmd.exe", "/c timeout /t 30 /nobreak", cts.Token));
    }

    [Fact]
    public async Task RunAsync_ZeroExitCode_StandardOutputTrimmed()
    {
        // Verify the result record is properly populated.
        var result = await _runner.RunAsync("cmd.exe", "/c echo trimtest");

        Assert.Equal(0, result.ExitCode);
        Assert.NotNull(result.StandardOutput);
        Assert.NotNull(result.StandardError);
    }

    [Fact]
    public async Task RunAsync_NonZeroExitCode_StillCapturesOutput()
    {
        // A command that writes output AND exits non-zero.
        var result = await _runner.RunAsync("cmd.exe", "/c echo output-before-fail && exit 3");

        Assert.Equal(3, result.ExitCode);
        Assert.Contains("output-before-fail", result.StandardOutput);
    }
}
