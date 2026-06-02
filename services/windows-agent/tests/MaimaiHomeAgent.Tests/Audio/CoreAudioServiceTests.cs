using MaimaiHomeAgent.Audio;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace MaimaiHomeAgent.Tests.Audio;

/// <summary>
/// Windows-only smoke tests for <see cref="CoreAudioService"/>. These tests
/// exercise the real Core Audio COM stack and require a Windows machine with
/// at least one audio device present.
///
/// Excluded from default <c>dotnet test</c> runs via the Integration trait;
/// run explicitly with:
///   dotnet test --filter "Category=Integration"
/// </summary>
[Trait("Category", "Integration")]
[Trait("Category", "Windows")]
public class CoreAudioServiceTests : IAsyncLifetime
{
    private CoreAudioService _service = null!;
    private AudioStaDispatcher _dispatcher = null!;

    public async Task InitializeAsync()
    {
        _dispatcher = new AudioStaDispatcher(NullLogger<AudioStaDispatcher>.Instance);
        await _dispatcher.StartAsync(CancellationToken.None);
        _service = new CoreAudioService(_dispatcher, NullLogger<CoreAudioService>.Instance);
    }

    public async Task DisposeAsync()
    {
        _service.Dispose();
        await _dispatcher.DisposeAsync();
    }

    [Fact]
    public async Task GetStateAsync_ReturnsSaneValues()
    {
        var state = await _service.GetStateAsync();

        Assert.NotNull(state);
        // Volume must be in [0.0, 1.0].
        Assert.InRange(state.MasterVolume, 0.0, 1.0);
        // DefaultDeviceId may be null if no default device is set, but the
        // record itself must be non-null (already asserted above).
    }

    [Fact]
    public async Task ListDevicesAsync_ReturnsNonNullCollection()
    {
        var devices = await _service.ListDevicesAsync();

        Assert.NotNull(devices);
        // On a Windows machine with audio hardware there should be at least
        // one device. We do not assert non-empty because headless CI VMs may
        // have no audio devices; we just verify the call succeeds.
    }

    [Fact]
    public async Task ListDevicesAsync_AllDevicesHaveNonEmptyNames()
    {
        var devices = await _service.ListDevicesAsync();

        foreach (var device in devices)
        {
            Assert.False(string.IsNullOrWhiteSpace(device.Name),
                $"Device {device.Id} has a null or empty name.");
        }
    }

    [Fact]
    public async Task ListDevicesAsync_AllDevicesHaveValidIds()
    {
        var devices = await _service.ListDevicesAsync();

        foreach (var device in devices)
        {
            Assert.NotEqual(Guid.Empty, device.Id);
        }
    }

    [Fact]
    public async Task ListDevicesAsync_AtMostOneDefaultDevice()
    {
        var devices = await _service.ListDevicesAsync();

        var defaultCount = devices.Count(d => d.IsDefault);
        Assert.True(defaultCount <= 1,
            $"Expected at most 1 default device but found {defaultCount}.");
    }

    [Fact]
    public async Task GetStateAsync_DefaultDeviceId_MatchesDefaultInDeviceList()
    {
        var state = await _service.GetStateAsync();
        var devices = await _service.ListDevicesAsync();

        if (state.DefaultDeviceId is null)
        {
            // No default device — list should have no default either.
            Assert.DoesNotContain(devices, d => d.IsDefault);
        }
        else
        {
            // The default device id from state must appear in the device list.
            Assert.Contains(devices, d => d.Id == state.DefaultDeviceId.Value);
        }
    }

    [Fact]
    public async Task SetVolumeAsync_OutOfRange_ThrowsArgumentOutOfRangeException()
    {
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(
            () => _service.SetVolumeAsync(1.5));
    }

    [Fact]
    public async Task SetVolumeAsync_NaN_ThrowsArgumentOutOfRangeException()
    {
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(
            () => _service.SetVolumeAsync(double.NaN));
    }
}
