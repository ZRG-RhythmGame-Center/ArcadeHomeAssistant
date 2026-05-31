using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Mvc.Testing;
using Xunit;

namespace MaimaiHomeAgent.Tests;

/// <summary>
/// Integration tests for the <c>GET /api/status</c> endpoint. Uses
/// <see cref="WebApplicationFactory{TEntryPoint}"/> to spin up the full
/// ASP.NET Core pipeline in-process.
///
/// Finding: the current /api/status response does not include a <c>baseUrl</c>
/// field. If the mobile client requires it, a follow-up task should add it to
/// Program.cs and update this test.
/// </summary>
public class StatusEndpointTests : IClassFixture<WebApplicationFactory<Program>>
{
    private readonly HttpClient _client;

    public StatusEndpointTests(WebApplicationFactory<Program> factory)
    {
        _client = factory.CreateClient();
    }

    [Fact]
    public async Task GetStatus_Returns200()
    {
        var response = await _client.GetAsync("/api/status");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task GetStatus_ResponseIsJson()
    {
        var response = await _client.GetAsync("/api/status");

        Assert.Equal("application/json", response.Content.Headers.ContentType?.MediaType);
    }

    [Fact]
    public async Task GetStatus_ContainsMachineName()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        Assert.True(doc.RootElement.TryGetProperty("machineName", out var prop));
        Assert.False(string.IsNullOrEmpty(prop.GetString()));
    }

    [Fact]
    public async Task GetStatus_ContainsVersion()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        Assert.True(doc.RootElement.TryGetProperty("version", out var prop));
        Assert.False(string.IsNullOrEmpty(prop.GetString()));
    }

    [Fact]
    public async Task GetStatus_ContainsUptimeSeconds()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        Assert.True(doc.RootElement.TryGetProperty("uptimeSeconds", out var prop));
        Assert.True(prop.GetInt64() >= 0);
    }

    [Fact]
    public async Task GetStatus_ContainsStartedAt()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        Assert.True(doc.RootElement.TryGetProperty("startedAt", out var prop));
        Assert.Equal(JsonValueKind.String, prop.ValueKind);
    }

    [Fact]
    public async Task GetStatus_ContainsCapabilities()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        Assert.True(doc.RootElement.TryGetProperty("capabilities", out var caps));
        Assert.Equal(JsonValueKind.Object, caps.ValueKind);
    }

    [Fact]
    public async Task GetStatus_Capabilities_ContainsAudioVolume()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        var caps = doc.RootElement.GetProperty("capabilities");
        Assert.True(caps.TryGetProperty("audioVolume", out var prop));
        Assert.Equal(JsonValueKind.True, prop.ValueKind);
    }

    [Fact]
    public async Task GetStatus_Capabilities_ContainsAudioMute()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        var caps = doc.RootElement.GetProperty("capabilities");
        Assert.True(caps.TryGetProperty("audioMute", out var prop));
        Assert.Equal(JsonValueKind.True, prop.ValueKind);
    }

    [Fact]
    public async Task GetStatus_Capabilities_ContainsAudioDeviceSwitch()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        var caps = doc.RootElement.GetProperty("capabilities");
        Assert.True(caps.TryGetProperty("audioDeviceSwitch", out var prop));
        Assert.Equal(JsonValueKind.True, prop.ValueKind);
    }

    [Fact]
    public async Task GetStatus_Capabilities_ContainsFileManagement()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        var caps = doc.RootElement.GetProperty("capabilities");
        Assert.True(caps.TryGetProperty("fileManagement", out var prop));
        Assert.Equal(JsonValueKind.True, prop.ValueKind);
    }

    [Fact]
    public async Task GetStatus_Capabilities_ContainsDiscoveryBroadcast()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        var caps = doc.RootElement.GetProperty("capabilities");
        Assert.True(caps.TryGetProperty("discoveryBroadcast", out var prop));
        Assert.Equal(JsonValueKind.True, prop.ValueKind);
    }

    [Fact]
    public async Task GetStatus_UptimeSeconds_IsNonNegative()
    {
        // Two calls in quick succession; second uptime must be >= first.
        var r1 = await _client.GetAsync("/api/status");
        var j1 = await r1.Content.ReadAsStringAsync();
        using var d1 = JsonDocument.Parse(j1);
        var uptime1 = d1.RootElement.GetProperty("uptimeSeconds").GetInt64();

        await Task.Delay(10);

        var r2 = await _client.GetAsync("/api/status");
        var j2 = await r2.Content.ReadAsStringAsync();
        using var d2 = JsonDocument.Parse(j2);
        var uptime2 = d2.RootElement.GetProperty("uptimeSeconds").GetInt64();

        Assert.True(uptime2 >= uptime1,
            $"Expected uptime to be non-decreasing but got {uptime1} then {uptime2}.");
    }
}
