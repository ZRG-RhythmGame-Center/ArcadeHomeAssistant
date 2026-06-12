using MaimaiHomeAgent.Admin;
using MaimaiHomeAgent.Files;
using MaimaiHomeAgent.Launcher;
using MaimaiHomeAgent.Power;
using MaimaiHomeAgent.Settings;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Tests.Settings;

public sealed class AgentSettingsServiceTests : IDisposable
{
    private readonly string _tempDir =
        Path.Combine(Path.GetTempPath(), "maimai-settings-tests-" + Guid.NewGuid().ToString("N"));

    public AgentSettingsServiceTests()
    {
        Directory.CreateDirectory(_tempDir);
    }

    public void Dispose()
    {
        if (Directory.Exists(_tempDir)) Directory.Delete(_tempDir, true);
    }

    [Fact]
    public async Task GetAsync_DoesNotExposeAdminPassword()
    {
        var service = CreateService("seganmsl");

        var settings = await service.GetAsync();

        Assert.True(settings.AdminPasswordConfigured);
    }

    [Fact]
    public async Task UpdateAsync_WithEmptyAdminPassword_DoesNotPersistPassword()
    {
        var configPath = Path.Combine(_tempDir, "appsettings.user.json");
        var service = CreateService(configPath: configPath);

        var result = await service.UpdateAsync(new AgentSettingsUpdateRequest(
            "",
            null,
            null,
            null,
            null));

        Assert.True(result.Success);
        Assert.False(File.Exists(configPath));
    }

    [Fact]
    public async Task UpdateAsync_WithDuplicateLauncherKey_PersistsUserConfig()
    {
        var configPath = Path.Combine(_tempDir, "appsettings.user.json");
        var service = CreateService(configPath: configPath);
        var launcher = new LauncherSettingsDto(
            false,
            1080,
            1920,
            "Left",
            "Right",
            "Enter",
            "F11",
            new[]
            {
                new LauncherItemSettingsDto("a", "A", "Title A", null, null, "echo a", null, "echo stop a", null, "A",
                    1, true),
                new LauncherItemSettingsDto("b", "B", "Title B", null, null, "echo b", null, "echo stop b", null, "a",
                    2, true)
            });

        var result = await service.UpdateAsync(new AgentSettingsUpdateRequest(null, null, launcher, null, null));

        Assert.True(result.Success);
        Assert.True(File.Exists(configPath));
    }

    [Fact]
    public async Task UpdateAsync_WithValidLauncher_PersistsUserConfig()
    {
        var configPath = Path.Combine(_tempDir, "appsettings.user.json");
        var service = CreateService(configPath: configPath);
        var launcher = new LauncherSettingsDto(
            true,
            1080,
            1920,
            "Left",
            "Right",
            "Enter",
            "F11",
            new[]
            {
                new LauncherItemSettingsDto("mai", "maimai", "maimai", null, null, "echo mai", null, "echo stop", null,
                    "M", 1, true)
            });

        var result = await service.UpdateAsync(new AgentSettingsUpdateRequest(null, null, launcher, null, null));

        Assert.True(result.Success);
        Assert.True(File.Exists(configPath));
        var text = await File.ReadAllTextAsync(configPath);
        Assert.Contains("ShowOnAgentStart", text);
        Assert.Contains("maimai", text);
    }

    [Fact]
    public async Task UpdateAsync_WithAutoStartEnabled_CallsAutoStartManager()
    {
        var autoStart = new FakeAutoStartManager();
        var service = CreateService(autoStart: autoStart);

        var result = await service.UpdateAsync(new AgentSettingsUpdateRequest(null, true, null, null, null));

        Assert.True(result.Success);
        Assert.True(autoStart.Enabled);
    }

    private AgentSettingsService CreateService(
        string adminPassword = "seganmsl",
        string? configPath = null,
        FakeAutoStartManager? autoStart = null)
    {
        var configuration = new ConfigurationBuilder()
            .AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["FileRoots:0"] = _tempDir
            })
            .Build();
        var fileRoots = new FileRootService(configuration, NullLogger<FileRootService>.Instance);
        return new AgentSettingsService(
            new StaticOptionsMonitor<AdminOptions>(new AdminOptions { Password = adminPassword }),
            new StaticOptionsMonitor<LauncherOptions>(new LauncherOptions()),
            new StaticOptionsMonitor<RemoteShutdownOptions>(new RemoteShutdownOptions()),
            fileRoots,
            autoStart ?? new FakeAutoStartManager(),
            configuration,
            configPath ?? Path.Combine(_tempDir, "appsettings.user.json"));
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
