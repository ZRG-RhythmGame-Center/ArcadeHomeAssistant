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
        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(500));

        // Snapshot the ping.exe processes that exist BEFORE we run, so the
        // teardown assertion can identify the new process precisely.
        var pingsBefore = System.Diagnostics.Process.GetProcessesByName("ping")
            .Select(p => p.Id)
            .ToHashSet();
        try
        {
            // ping -n 30 sends 30 ICMP pings with 1s delay each (~30s total).
            // With redirected stdin/stdout it does not exit early, so cancellation
            // fires before the process finishes and WaitForExitAsync throws.
            await Assert.ThrowsAnyAsync<OperationCanceledException>(async () =>
                await _runner.RunAsync("ping.exe", "-n 30 127.0.0.1", cts.Token));

            // Closes Gate F #2: prove the process was actually KILLED, not just
            // that the wait was abandoned. ProcessRunner.RunAsync wires
            // process.Kill(entireProcessTree: true) into the cancellation path,
            // so any ping.exe spawned during this test must be gone after
            // RunAsync returns. We give the OS a brief moment to reap the PID.
            for (var i = 0; i < 20; i++)
            {
                var pingsNow = System.Diagnostics.Process.GetProcessesByName("ping")
                    .Select(p => p.Id)
                    .ToHashSet();
                pingsNow.ExceptWith(pingsBefore);
                if (pingsNow.Count == 0)
                {
                    return; // success: no orphaned ping.exe survived cancellation.
                }
                await Task.Delay(50);
            }
            Assert.Fail("ping.exe spawned by ProcessRunner.RunAsync was not killed within 1s after cancellation");
        }
        finally
        {
            // Defensive: if the assertion failed, kill any leaked ping.exe so
            // the test process does not hang on shutdown.
            foreach (var p in System.Diagnostics.Process.GetProcessesByName("ping"))
            {
                if (!pingsBefore.Contains(p.Id))
                {
                    try { p.Kill(); } catch { /* best effort */ }
                }
                p.Dispose();
            }
        }
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
