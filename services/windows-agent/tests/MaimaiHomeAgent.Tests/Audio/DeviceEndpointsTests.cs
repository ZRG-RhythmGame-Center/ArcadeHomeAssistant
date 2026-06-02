using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using MaimaiHomeAgent.Audio;
using MaimaiHomeAgent.Realtime;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;
using Xunit;

namespace MaimaiHomeAgent.Tests.Audio;

/// <summary>
/// Integration tests for the /api/audio/devices and /api/audio/default-device
/// endpoints registered via <c>DeviceEndpoints.MapDeviceEndpoints</c>. Mirrors
/// the host pattern in <see cref="AudioEndpointsTests"/> with a mocked
/// <see cref="IAudioService"/>.
/// </summary>
public class DeviceEndpointsTests : IAsyncLifetime
{
    private WebApplication _app = null!;
    private HttpClient _client = null!;
    private Mock<IAudioService> _audioMock = null!;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateBuilder();
        _audioMock = new Mock<IAudioService>(MockBehavior.Strict);
        builder.Services.AddSingleton<IAudioService>(_audioMock.Object);
        builder.Services.AddSingleton<EventHub>(sp => new EventHub(NullLogger<EventHub>.Instance));
        builder.Services.AddSingleton<EventPublisher>();
        builder.WebHost.UseTestServer();

        _app = builder.Build();
        _app.MapDeviceEndpoints();

        await _app.StartAsync();
        _client = _app.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        await _app.StopAsync();
        await _app.DisposeAsync();
        _client.Dispose();
    }

    [Fact]
    public async Task GetDevices_Returns200WithProjectedShape()
    {
        var id1 = Guid.NewGuid();
        var id2 = Guid.NewGuid();
        _audioMock
            .Setup(s => s.ListDevicesAsync())
            .ReturnsAsync(new[]
            {
                new AudioDevice(id1, "Speakers", true, DeviceState.Active),
                new AudioDevice(id2, "Headphones", false, DeviceState.Disabled),
            });

        var response = await _client.GetAsync("/api/audio/devices");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        Assert.Equal(JsonValueKind.Array, root.ValueKind);
        Assert.Equal(2, root.GetArrayLength());

        var first = root[0];
        Assert.Equal(id1.ToString(), first.GetProperty("id").GetString());
        Assert.Equal("Speakers", first.GetProperty("name").GetString());
        Assert.True(first.GetProperty("isDefault").GetBoolean());
        Assert.Equal("active", first.GetProperty("state").GetString());

        var second = root[1];
        Assert.Equal("disabled", second.GetProperty("state").GetString());
        Assert.False(second.GetProperty("isDefault").GetBoolean());
    }

    [Fact]
    public async Task GetDevices_EmptyList_Returns200WithEmptyArray()
    {
        _audioMock
            .Setup(s => s.ListDevicesAsync())
            .ReturnsAsync(Array.Empty<AudioDevice>());

        var response = await _client.GetAsync("/api/audio/devices");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal(JsonValueKind.Array, doc.RootElement.ValueKind);
        Assert.Equal(0, doc.RootElement.GetArrayLength());
    }

    [Fact]
    public async Task PostDefaultDevice_ValidGuid_Returns200WithDeviceList()
    {
        var deviceId = Guid.NewGuid();
        _audioMock
            .Setup(s => s.SetDefaultDeviceAsync(deviceId))
            .Returns(Task.CompletedTask);
        _audioMock
            .Setup(s => s.ListDevicesAsync())
            .ReturnsAsync(new[]
            {
                new AudioDevice(deviceId, "Headphones", true, DeviceState.Active),
            });

        var response = await _client.PostAsJsonAsync(
            "/api/audio/default-device",
            new { deviceId = deviceId.ToString() });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal(JsonValueKind.Array, doc.RootElement.ValueKind);
        Assert.Equal(deviceId.ToString(), doc.RootElement[0].GetProperty("id").GetString());
        _audioMock.Verify(s => s.SetDefaultDeviceAsync(deviceId), Times.Once);
    }

    [Fact]
    public async Task PostDefaultDevice_InvalidGuid_Returns400WithInvalidDeviceIdError()
    {
        var response = await _client.PostAsJsonAsync(
            "/api/audio/default-device",
            new { deviceId = "not-a-guid" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("invalid_device_id", doc.RootElement.GetProperty("error").GetString());
        _audioMock.Verify(s => s.SetDefaultDeviceAsync(It.IsAny<Guid>()), Times.Never);
    }

    [Fact]
    public async Task PostDefaultDevice_EmptyDeviceId_Returns400()
    {
        var response = await _client.PostAsJsonAsync(
            "/api/audio/default-device",
            new { deviceId = "" });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("invalid_device_id", doc.RootElement.GetProperty("error").GetString());
    }

    [Fact]
    public async Task PostDefaultDevice_DeviceNotFound_Returns404()
    {
        var deviceId = Guid.NewGuid();
        _audioMock
            .Setup(s => s.SetDefaultDeviceAsync(deviceId))
            .ThrowsAsync(new AudioDeviceNotFoundException(deviceId));

        var response = await _client.PostAsJsonAsync(
            "/api/audio/default-device",
            new { deviceId = deviceId.ToString() });

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("device_not_found", doc.RootElement.GetProperty("error").GetString());
        _audioMock.Verify(s => s.ListDevicesAsync(), Times.Never);
    }

    [Fact]
    public async Task GetDevices_CanceledOperation_Returns502WithMessage()
    {
        _audioMock
            .Setup(s => s.ListDevicesAsync())
            .ThrowsAsync(new TaskCanceledException("A task was canceled."));

        var response = await _client.GetAsync("/api/audio/devices");

        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("device_unavailable", doc.RootElement.GetProperty("error").GetString());
        Assert.Equal("音频设备不可用：A task was canceled.", doc.RootElement.GetProperty("message").GetString());
    }

    [Fact]
    public async Task PostDefaultDevice_CanceledOperation_Returns502WithMessage()
    {
        var deviceId = Guid.NewGuid();
        _audioMock
            .Setup(s => s.SetDefaultDeviceAsync(deviceId))
            .ThrowsAsync(new TaskCanceledException("A task was canceled."));

        var response = await _client.PostAsJsonAsync(
            "/api/audio/default-device",
            new { deviceId = deviceId.ToString() });

        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode);
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);
        Assert.Equal("device_unavailable", doc.RootElement.GetProperty("error").GetString());
        Assert.Equal("音频设备不可用：A task was canceled.", doc.RootElement.GetProperty("message").GetString());
    }

    [Fact]
    public void Project_MapsAllDeviceStatesToLowerInvariantStrings()
    {
        var devices = new[]
        {
            new AudioDevice(Guid.NewGuid(), "A", false, DeviceState.Active),
            new AudioDevice(Guid.NewGuid(), "B", false, DeviceState.Disabled),
            new AudioDevice(Guid.NewGuid(), "C", false, DeviceState.NotPresent),
            new AudioDevice(Guid.NewGuid(), "D", false, DeviceState.Unplugged),
        };

        var projected = DeviceEndpoints.Project(devices);

        Assert.Collection(projected,
            d => Assert.Equal("active", d.State),
            d => Assert.Equal("disabled", d.State),
            d => Assert.Equal("notpresent", d.State),
            d => Assert.Equal("unplugged", d.State));
    }
}
