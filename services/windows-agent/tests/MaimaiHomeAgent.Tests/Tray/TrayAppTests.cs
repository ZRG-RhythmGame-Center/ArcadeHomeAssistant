using MaimaiHomeAgent.Launcher;
using MaimaiHomeAgent.Settings;
using MaimaiHomeAgent.Startup;
using MaimaiHomeAgent.Tray;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Moq;

namespace MaimaiHomeAgent.Tests.Tray;

/// <summary>
///     Characterization tests for <see cref="TrayApp" /> using the
///     <see cref="ITrayIconHost" /> and <see cref="IUiThreadPump" /> seams.
///     These tests verify the observable contract of TrayApp without spinning up
///     a real Win32 message pump or NotifyIcon.
/// </summary>
public class TrayAppTests
{
    private readonly AutoStartManager _autoStart;
    private readonly Mock<ITrayIconHost> _hostMock;
    private readonly Mock<ILauncherService> _launcherMock;
    private readonly Mock<IHostApplicationLifetime> _lifetimeMock;
    private readonly Mock<IUiThreadPump> _pumpMock;
    private readonly Mock<IProcessRunner> _runnerMock;
    private readonly Mock<ISettingsWindowHost> _settingsWindowMock;
    private readonly TrayApp _trayApp;

    public TrayAppTests()
    {
        _hostMock = new Mock<ITrayIconHost>(MockBehavior.Strict);
        _pumpMock = new Mock<IUiThreadPump>(MockBehavior.Strict);
        _runnerMock = new Mock<IProcessRunner>(MockBehavior.Loose);
        _launcherMock = new Mock<ILauncherService>(MockBehavior.Strict);
        _settingsWindowMock = new Mock<ISettingsWindowHost>(MockBehavior.Strict);
        _lifetimeMock = new Mock<IHostApplicationLifetime>(MockBehavior.Loose);

        _autoStart = new AutoStartManager(
            _runnerMock.Object,
            NullLogger<AutoStartManager>.Instance);

        // IUiThreadPump.Start: immediately invoke the onReady callback on the
        // calling thread (simulates the UI thread being ready synchronously).
        _pumpMock
            .Setup(p => p.Start(It.IsAny<Action>()))
            .Callback<Action>(onReady => onReady());

        _pumpMock.Setup(p => p.Stop());

        _pumpMock
            .Setup(p => p.RunOnUiThread(It.IsAny<Action>()))
            .Callback<Action>(action => action());
        _pumpMock
            .Setup(p => p.RegisterStopShortcut(It.IsAny<string>(), It.IsAny<Action>()));

        _hostMock.Setup(h => h.Create());
        _hostMock.Setup(h => h.UpdateAutoStartChecked(It.IsAny<bool>()));
        _hostMock.Setup(h => h.Dispose());
        _settingsWindowMock
            .Setup(w => w.ShowAsync(It.IsAny<CancellationToken>()))
            .Returns(Task.CompletedTask);
        _launcherMock
            .Setup(l => l.ShowAsync(It.IsAny<CancellationToken>()))
            .ReturnsAsync(LauncherActionResult.Ok(new LauncherStatusDto(false, false, null, null, "idle", null)));
        _launcherMock
            .Setup(l => l.StopActiveItemAsync(It.IsAny<CancellationToken>()))
            .ReturnsAsync(LauncherActionResult.Ok(new LauncherStatusDto(true, false, null, null, "idle", null)));

        // IsEnabledAsync: return false (task not registered).
        _runnerMock
            .Setup(r => r.RunAsync(It.IsAny<string>(), It.IsAny<string>(), It.IsAny<CancellationToken>()))
            .ReturnsAsync(new ProcessResult(1, string.Empty, "ERROR: not found"));

        _trayApp = new TrayApp(
            _autoStart,
            _launcherMock.Object,
            _settingsWindowMock.Object,
            new StaticOptionsMonitor<LauncherOptions>(new LauncherOptions()),
            _lifetimeMock.Object,
            NullLogger<TrayApp>.Instance,
            _hostMock.Object,
            _pumpMock.Object);
    }

    [Fact]
    public async Task StartAsync_CallsTrayIconHostCreate()
    {
        await _trayApp.StartAsync(CancellationToken.None);

        _hostMock.Verify(h => h.Create(), Times.Once);
    }

    [Fact]
    public async Task StartAsync_StartsPump()
    {
        await _trayApp.StartAsync(CancellationToken.None);

        _pumpMock.Verify(p => p.Start(It.IsAny<Action>()), Times.Once);
    }

    [Fact]
    public async Task StopAsync_DisposesHost()
    {
        await _trayApp.StartAsync(CancellationToken.None);
        await _trayApp.StopAsync(CancellationToken.None);

        _hostMock.Verify(h => h.Dispose(), Times.Once);
    }

    [Fact]
    public async Task StopAsync_StopsPump()
    {
        await _trayApp.StartAsync(CancellationToken.None);
        await _trayApp.StopAsync(CancellationToken.None);

        _pumpMock.Verify(p => p.Stop(), Times.Once);
    }

    [Fact]
    public async Task StopAsync_WithoutStart_DoesNotThrow()
    {
        // StopAsync before StartAsync must be safe.
        await _trayApp.StopAsync(CancellationToken.None);
    }

    [Fact]
    public async Task OnExit_CallsLifetimeStopApplication()
    {
        await _trayApp.StartAsync(CancellationToken.None);

        _trayApp.SimulateExit();

        _lifetimeMock.Verify(l => l.StopApplication(), Times.Once);
    }

    [Fact]
    public async Task OnToggleAutoStart_Enable_UpdatesHostCheckedState()
    {
        // Arrange: IsEnabled returns false (not registered), Enable returns success.
        _runnerMock
            .SetupSequence(r => r.RunAsync(
                It.IsAny<string>(),
                It.Is<string>(a => a.Contains("/Query")),
                It.IsAny<CancellationToken>()))
            .ReturnsAsync(new ProcessResult(1, string.Empty, "not found")) // initial IsEnabled
            .ReturnsAsync(new ProcessResult(0, BuildTaskXml(Environment.ProcessPath ?? "app.exe"),
                string.Empty)); // after enable

        _runnerMock
            .Setup(r => r.RunAsync(
                It.IsAny<string>(),
                It.Is<string>(a => a.Contains("/Create")),
                It.IsAny<CancellationToken>()))
            .ReturnsAsync(new ProcessResult(0, string.Empty, string.Empty));

        await _trayApp.StartAsync(CancellationToken.None);

        await _trayApp.SimulateToggleAutoStartAsync();

        // After toggle, UpdateAutoStartChecked should have been called.
        _hostMock.Verify(
            h => h.UpdateAutoStartChecked(It.IsAny<bool>()),
            Times.AtLeastOnce);
    }

    [Fact]
    public async Task OnOpenSettings_ShowsSettingsWindow()
    {
        await _trayApp.StartAsync(CancellationToken.None);

        await _trayApp.SimulateOpenSettingsAsync();

        _settingsWindowMock.Verify(w => w.ShowAsync(It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task OnOpenLauncher_ShowsLauncher()
    {
        await _trayApp.StartAsync(CancellationToken.None);

        await _trayApp.SimulateOpenLauncherAsync();

        _launcherMock.Verify(l => l.ShowAsync(It.IsAny<CancellationToken>()), Times.Once);
    }

    [Fact]
    public async Task StartAsync_RegistersStopShortcut()
    {
        await _trayApp.StartAsync(CancellationToken.None);

        _pumpMock.Verify(p => p.RegisterStopShortcut("F11", It.IsAny<Action>()), Times.Once);
    }

    [Fact]
    public async Task DisposeAsync_IsIdempotent()
    {
        await _trayApp.StartAsync(CancellationToken.None);

        await _trayApp.DisposeAsync();
        await _trayApp.DisposeAsync();

        // Dispose must not throw on second call.
        _hostMock.Verify(h => h.Dispose(), Times.AtLeastOnce);
    }

    private static string BuildTaskXml(string command)
    {
        return $"""
                <?xml version="1.0" encoding="UTF-16"?>
                <Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
                  <Actions Context="Author">
                    <Exec>
                      <Command>{command}</Command>
                    </Exec>
                  </Actions>
                </Task>
                """;
    }

    private sealed class StaticOptionsMonitor<T> : IOptionsMonitor<T>
    {
        public StaticOptionsMonitor(T value)
        {
            CurrentValue = value;
        }

        public T CurrentValue { get; }

        public T Get(string? name)
        {
            return CurrentValue;
        }

        public IDisposable? OnChange(Action<T, string?> listener)
        {
            return null;
        }
    }
}
