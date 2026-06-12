using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using MaimaiHomeAgent.Power;
using MaimaiHomeAgent.Realtime;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;

namespace MaimaiHomeAgent.Tests.Power;

public sealed class PowerEndpointsTests : IAsyncLifetime
{
    private WebApplication _app = null!;
    private HttpClient _client = null!;
    private FakeRemoteShutdownExecutor _executor = null!;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();

        _executor = new FakeRemoteShutdownExecutor();
        builder.Services.Configure<RemoteShutdownOptions>(options =>
        {
            options.Enabled = true;
            options.ControlToken = "secret-token";
        });
        builder.Services.AddSingleton<IRemoteShutdownExecutor>(_executor);
        builder.Services.AddSingleton<EventHub>(sp => new EventHub(NullLogger<EventHub>.Instance));
        builder.Services.AddSingleton<EventPublisher>();
        builder.Services.AddSingleton<IRemoteShutdownService, RemoteShutdownService>();

        _app = builder.Build();
        _app.MapPowerEndpoints();
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
    public async Task GetShutdownStatus_WhenConfigured_ReturnsAvailable()
    {
        var response = await _client.GetAsync("/api/power/shutdown");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        AssertCurrentStatusShape(doc.RootElement);
        Assert.True(doc.RootElement.GetProperty("available").GetBoolean());
        Assert.Equal("idle", doc.RootElement.GetProperty("state").GetString());
    }

    [Fact]
    public async Task Execute_WithoutToken_Returns401AndDoesNotExecute()
    {
        var response = await _client.PostAsJsonAsync("/api/power/shutdown", new { confirm = true });

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("unauthorized", doc.RootElement.GetProperty("error").GetString());
        Assert.Equal(0, _executor.ExecuteCalls);
    }

    [Fact]
    public async Task Shutdown_WithToken_ExecutesImmediately()
    {
        Authorize();

        var response = await _client.PostAsJsonAsync("/api/power/shutdown", new { confirm = true });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        AssertCurrentStatusShape(doc.RootElement);
        Assert.Equal("executing", doc.RootElement.GetProperty("state").GetString());
        Assert.Equal(1, _executor.ExecuteCalls);
    }

    [Fact]
    public async Task Execute_WithoutConfirm_Returns400AndDoesNotExecute()
    {
        Authorize();

        var response = await _client.PostAsJsonAsync("/api/power/shutdown", new { confirm = false });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal(0, _executor.ExecuteCalls);
    }

    [Fact]
    public async Task Execute_WhenFeatureUnsupported_Returns503()
    {
        _executor.IsSupportedValue = false;
        Authorize();

        var response = await _client.PostAsJsonAsync("/api/power/shutdown", new { confirm = true });

        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("remote_shutdown_unavailable", doc.RootElement.GetProperty("error").GetString());
        AssertCurrentStatusShape(doc.RootElement.GetProperty("status"));
    }

    [Fact]
    public async Task Shutdown_WhenExecutorFails_Returns502AndExposesFailedStatus()
    {
        _executor.Failure = new RemoteShutdownExecutionException("blocked by policy");
        Authorize();

        var response = await _client.PostAsJsonAsync("/api/power/shutdown", new { confirm = true });

        Assert.Equal(HttpStatusCode.BadGateway, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("shutdown_failed", doc.RootElement.GetProperty("error").GetString());
        var status = doc.RootElement.GetProperty("status");
        AssertCurrentStatusShape(status);
        Assert.Equal("failed", status.GetProperty("state").GetString());
        Assert.Equal("blocked by policy", status.GetProperty("error").GetString());
    }

    private void Authorize()
    {
        _client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "secret-token");
    }

    private static void AssertCurrentStatusShape(JsonElement status)
    {
        var names = status.EnumerateObject().Select(property => property.Name).Order().ToArray();
        Assert.Equal(new[] { "available", "error", "state" }, names);
    }

    private sealed class FakeRemoteShutdownExecutor : IRemoteShutdownExecutor
    {
        public bool IsSupportedValue { get; set; } = true;
        public Exception? Failure { get; set; }
        public int ExecuteCalls { get; private set; }

        public bool IsSupported => IsSupportedValue;

        public Task ExecuteShutdownAsync(CancellationToken ct = default)
        {
            ExecuteCalls++;
            if (Failure is not null) throw Failure;
            return Task.CompletedTask;
        }
    }
}