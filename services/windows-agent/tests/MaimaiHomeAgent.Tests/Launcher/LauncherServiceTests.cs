using MaimaiHomeAgent.Launcher;
using MaimaiHomeAgent.Realtime;
using MaimaiHomeAgent.Startup;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Tests.Launcher;

public sealed class LauncherServiceTests
{
    [Fact]
    public async Task ShowAsync_ShowsWindowWithEnabledItems()
    {
        var window = new FakeLauncherWindowHost();
        var service = CreateService(window: window);

        var result = await service.ShowAsync();

        Assert.True(result.Accepted);
        Assert.True(window.ShowCalled);
        Assert.Single(window.LastItems);
    }

    [Fact]
    public async Task HideAsync_HidesWindow()
    {
        var window = new FakeLauncherWindowHost();
        var service = CreateService(window: window);
        await service.ShowAsync();

        var result = await service.HideAsync();

        Assert.True(result.Accepted);
        Assert.True(window.HideCalled);
        Assert.False(result.Status.IsVisible);
    }

    [Fact]
    public async Task StartItemAsync_WithKnownItem_RunsStartCommandAndMinimizesWindow()
    {
        var runner = new FakeProcessRunner();
        var window = new FakeLauncherWindowHost();
        var service = CreateService(runner, window);

        var result = await service.StartItemAsync("mai");

        Assert.True(result.Accepted);
        Assert.True(window.MinimizeCalled);
        Assert.Equal("running", result.Status.State);
        var call = Assert.Single(runner.Calls);
        Assert.Equal("cmd.exe", call.FileName);
        Assert.Contains("start-mai", call.Arguments);
    }

    [Fact]
    public async Task StartItemAsync_WhenAlreadyActive_ReturnsConflictResult()
    {
        var service = CreateService();
        await service.StartItemAsync("mai");

        var result = await service.StartItemAsync("mai");

        Assert.False(result.Accepted);
        Assert.Equal("launcher_item_already_active", result.Error);
    }

    [Fact]
    public async Task StopActiveItemAsync_RunsStopCommandAndShowsWindow()
    {
        var runner = new FakeProcessRunner();
        var window = new FakeLauncherWindowHost();
        var service = CreateService(runner, window);
        await service.StartItemAsync("mai");

        var result = await service.StopActiveItemAsync();

        Assert.True(result.Accepted);
        Assert.Equal("idle", result.Status.State);
        Assert.Equal(2, runner.Calls.Count);
        Assert.Contains("stop-mai", runner.Calls[1].Arguments);
        Assert.True(window.ShowCalled);
    }

    [Fact]
    public async Task StopActiveItemAsync_WithoutActiveItem_ReturnsRejected()
    {
        var service = CreateService();

        var result = await service.StopActiveItemAsync();

        Assert.False(result.Accepted);
        Assert.Equal("launcher_item_not_active", result.Error);
    }

    [Fact]
    public async Task StartItemAsync_WhenCommandFails_ReturnsRejected()
    {
        var runner = new FakeProcessRunner { NextResult = new ProcessResult(7, string.Empty, "boom") };
        var service = CreateService(runner);

        var result = await service.StartItemAsync("mai");

        Assert.False(result.Accepted);
        Assert.Equal("launcher_item_start_failed", result.Error);
        Assert.Equal("failed", result.Status.State);
    }

    private static LauncherService CreateService(
        FakeProcessRunner? runner = null,
        FakeLauncherWindowHost? window = null,
        LauncherOptions? options = null)
    {
        var hub = new EventHub(NullLogger<EventHub>.Instance);
        return new LauncherService(
            new StaticOptionsMonitor<LauncherOptions>(options ?? CreateOptions()),
            runner ?? new FakeProcessRunner(),
            window ?? new FakeLauncherWindowHost(),
            new EventPublisher(hub),
            NullLogger<LauncherService>.Instance);
    }

    private static LauncherOptions CreateOptions() => new()
    {
        Items = new()
        {
            new LauncherItemOptions
            {
                Id = "mai",
                Name = "maimai",
                CommandLine = "start-mai",
                StopCommandLine = "stop-mai",
                Key = "A",
                Enabled = true
            },
            new LauncherItemOptions
            {
                Id = "disabled",
                Name = "Disabled",
                CommandLine = "start-disabled",
                StopCommandLine = "stop-disabled",
                Key = "B",
                Enabled = false
            }
        }
    };

    private sealed class FakeProcessRunner : IProcessRunner
    {
        public ProcessResult NextResult { get; set; } = new(0, string.Empty, string.Empty);

        public List<(string FileName, string Arguments)> Calls { get; } = new();

        public Task<ProcessResult> RunAsync(string fileName, string arguments, CancellationToken ct = default)
        {
            Calls.Add((fileName, arguments));
            return Task.FromResult(NextResult);
        }
    }

    private sealed class FakeLauncherWindowHost : ILauncherWindowHost
    {
        public bool IsVisible { get; private set; }
        public bool ShowCalled { get; private set; }
        public bool MinimizeCalled { get; private set; }
        public bool HideCalled { get; private set; }
        public IReadOnlyList<LauncherItemRuntime> LastItems { get; private set; } = Array.Empty<LauncherItemRuntime>();
        public LauncherNavigationOptions? LastNavigation { get; private set; }

        public Task ShowAsync(
            IReadOnlyList<LauncherItemRuntime> items,
            LauncherNavigationOptions navigation,
            Func<string, CancellationToken, Task> onKeySelected,
            CancellationToken ct = default)
        {
            ShowCalled = true;
            IsVisible = true;
            LastItems = items;
            LastNavigation = navigation;
            return Task.CompletedTask;
        }

        public Task MinimizeAsync(CancellationToken ct = default)
        {
            MinimizeCalled = true;
            IsVisible = false;
            return Task.CompletedTask;
        }

        public Task HideAsync(CancellationToken ct = default)
        {
            HideCalled = true;
            IsVisible = false;
            return Task.CompletedTask;
        }
    }

    private sealed class StaticOptionsMonitor<T> : IOptionsMonitor<T>
    {
        public StaticOptionsMonitor(T value)
        {
            CurrentValue = value;
        }

        public T CurrentValue { get; }

        public T Get(string? name) => CurrentValue;

        public IDisposable? OnChange(Action<T, string?> listener) => null;
    }
}
