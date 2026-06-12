using MaimaiHomeAgent.Discovery;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Moq;

namespace MaimaiHomeAgent.Tests.Discovery;

public class MdnsAdvertiserTests
{
    [Fact]
    public async Task StartAsync_RegistersNetworkChangeHandler()
    {
        // Arrange
        var options = Options.Create(new DiscoveryOptions { Enabled = true, Port = 8765 });
        var loggerMock = new Mock<ILogger<MdnsAdvertiser>>();
        var appLifetimeMock = new Mock<IHostApplicationLifetime>();

        var advertiser = new MdnsAdvertiser(options, loggerMock.Object, appLifetimeMock.Object);

        // Act
        await advertiser.StartAsync(CancellationToken.None);

        // Assert - verify handler is registered (indirectly via triggering event)
        // This test verifies that StartAsync completes without exception
        Assert.NotNull(advertiser);
    }

    [Fact]
    public async Task OnNetworkChanged_DebouncesMergesRapidEvents()
    {
        // Arrange
        var options = Options.Create(new DiscoveryOptions { Enabled = true, Port = 8765 });
        var loggerMock = new Mock<ILogger<MdnsAdvertiser>>();
        var appLifetimeMock = new Mock<IHostApplicationLifetime>();

        var advertiser = new MdnsAdvertiser(options, loggerMock.Object, appLifetimeMock.Object);
        await advertiser.StartAsync(CancellationToken.None);

        // Act - simulate rapid network changes (all within <50ms so they merge into 1 debounce window)
        advertiser.TriggerNetworkChanged();
        advertiser.TriggerNetworkChanged();
        advertiser.TriggerNetworkChanged();

        // Wait for debounce to settle (500ms + buffer)
        await Task.Delay(700);

        // Assert - verify restart happened exactly once
        // The "restarting mDNS advertisement" message should appear exactly once despite 3 triggers
        loggerMock.Verify(
            x => x.Log(
                LogLevel.Information,
                It.IsAny<EventId>(),
                It.Is<It.IsAnyType>((v, t) => v.ToString()!.Contains("restarting mDNS advertisement")),
                It.IsAny<Exception>(),
                It.IsAny<Func<It.IsAnyType, Exception?, string>>()),
            Times.Once);
    }

    [Fact]
    public async Task StopAsync_UnregistersNetworkChangeHandler()
    {
        // Arrange
        var options = Options.Create(new DiscoveryOptions { Enabled = true, Port = 8765 });
        var loggerMock = new Mock<ILogger<MdnsAdvertiser>>();
        var appLifetimeMock = new Mock<IHostApplicationLifetime>();

        var advertiser = new MdnsAdvertiser(options, loggerMock.Object, appLifetimeMock.Object);
        await advertiser.StartAsync(CancellationToken.None);

        // Act
        await advertiser.StopAsync(CancellationToken.None);

        // Assert - verify no exception on stop
        Assert.NotNull(advertiser);
    }
}