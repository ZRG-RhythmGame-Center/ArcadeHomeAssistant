using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Xunit;

namespace MaimaiHomeAgent.Tests;

/// <summary>
/// Integration tests for the <c>GET /api/status</c> endpoint. Uses
/// <see cref="WebApplicationFactory{TEntryPoint}"/> to spin up the full
/// ASP.NET Core pipeline in-process.
///
/// Joined to the "WafProgramTests" collection so it shares the same
/// serialization context as the other WAF-based test classes and avoids
/// Serilog static-logger conflicts.
/// </summary>
[Collection("WafProgramTests")]
public class StatusEndpointTests : IDisposable
{
    private readonly StatusTestFactory _factory;
    private readonly HttpClient _client;

    public StatusEndpointTests()
    {
        _factory = new StatusTestFactory();
        _client = _factory.CreateClient();
    }

    public void Dispose()
    {
        _client.Dispose();
        _factory.Dispose();
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
    public async Task GetStatus_Capabilities_ContainsRemoteShutdownFalseByDefault()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        var caps = doc.RootElement.GetProperty("capabilities");
        Assert.True(caps.TryGetProperty("remoteShutdown", out var prop));
        Assert.Equal(JsonValueKind.False, prop.ValueKind);
    }

    [Fact]
    public async Task GetStatus_Capabilities_ContainsSettingsManagement()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        var caps = doc.RootElement.GetProperty("capabilities");
        Assert.True(caps.TryGetProperty("settingsManagement", out var prop));
        Assert.Equal(JsonValueKind.True, prop.ValueKind);
    }

    [Fact]
    public async Task GetStatus_Capabilities_ContainsLauncher()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        var caps = doc.RootElement.GetProperty("capabilities");
        Assert.True(caps.TryGetProperty("launcher", out var prop));
        Assert.Equal(JsonValueKind.True, prop.ValueKind);
    }

    /// <summary>
    /// /api/status includes a baseUrl field for mobile AgentStatus.baseUrl.
    /// </summary>
    [Fact]
    public async Task GetStatus_ContainsBaseUrl()
    {
        var response = await _client.GetAsync("/api/status");
        var json = await response.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(json);

        Assert.True(doc.RootElement.TryGetProperty("baseUrl", out var prop));
        Assert.Equal(JsonValueKind.String, prop.ValueKind);
        var baseUrl = prop.GetString();
        Assert.False(string.IsNullOrWhiteSpace(baseUrl));
        // Must be a well-formed http(s) URL so the mobile client can use it.
        Assert.True(
            Uri.TryCreate(baseUrl, UriKind.Absolute, out var uri) &&
            (uri.Scheme == "http" || uri.Scheme == "https"),
            $"baseUrl must be an absolute http(s) URL but was '{baseUrl}'");
    }

    [Fact]
    public async Task GetStatus_UptimeSeconds_IsNonNegative()
    {
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

    // ------------------------------------------------------------------ //
    //  Factory                                                             //
    // ------------------------------------------------------------------ //

    private sealed class StatusTestFactory : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Testing");

            // Strip background hosted services (mDNS, heartbeat, STA dispatcher,
            // TrayApp, DeviceChangeNotifier) so the test host doesn't open sockets,
            // spin up COM, or create Win32 windows.
            builder.ConfigureServices(services =>
            {
                var hosted = services
                    .Where(d => d.ServiceType == typeof(IHostedService))
                    .ToList();
                foreach (var d in hosted)
                {
                    services.Remove(d);
                }
            });
        }
    }
}
