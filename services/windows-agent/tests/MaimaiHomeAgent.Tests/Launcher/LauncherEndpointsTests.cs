using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MaimaiHomeAgent.Launcher;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace MaimaiHomeAgent.Tests.Launcher;

public sealed class LauncherEndpointsTests : IAsyncLifetime
{
    private WebApplication _app = null!;
    private HttpClient _client = null!;
    private FakeLauncherService _launcher = null!;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        _launcher = new FakeLauncherService();
        builder.Services.AddSingleton<ILauncherService>(_launcher);

        _app = builder.Build();
        _app.MapLauncherEndpoints();
        await _app.StartAsync();
        _client = _app.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        await _app.DisposeAsync();
        _client.Dispose();
    }

    [Fact]
    public async Task GetStatus_WithoutAuthorization_ReturnsStatus()
    {
        // LAN-only deployment: no Bearer token required.
        var response = await _client.GetAsync("/api/launcher/status");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("idle", doc.RootElement.GetProperty("state").GetString());
    }

    [Fact]
    public async Task Start_WithItemId_CallsLauncherService()
    {
        var response = await _client.PostAsJsonAsync("/api/launcher/start", new { itemId = "mai" });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("mai", _launcher.StartedItemId);
    }

    [Fact]
    public async Task Stop_WhenRejected_Returns409()
    {
        _launcher.StopResult =
            LauncherActionResult.Rejected(_launcher.GetStatus(), "launcher_item_not_active", "当前没有正在运行的启动项");

        var response = await _client.PostAsync("/api/launcher/stop", null);

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
    }

    private sealed class FakeLauncherService : ILauncherService
    {
        private readonly LauncherStatusDto _status = new(false, false, null, null, "idle", null);

        public string? StartedItemId { get; private set; }

        public LauncherActionResult? StopResult { get; set; }

        public LauncherStatusDto GetStatus()
        {
            return _status;
        }

        public Task<LauncherActionResult> ShowAsync(CancellationToken ct = default)
        {
            return Task.FromResult(LauncherActionResult.Ok(_status));
        }

        public Task<LauncherActionResult> HideAsync(CancellationToken ct = default)
        {
            return Task.FromResult(LauncherActionResult.Ok(_status));
        }

        public Task<LauncherActionResult> StartItemAsync(string itemId, CancellationToken ct = default)
        {
            StartedItemId = itemId;
            return Task.FromResult(LauncherActionResult.Ok(_status));
        }

        public Task<LauncherActionResult> StopActiveItemAsync(CancellationToken ct = default)
        {
            return Task.FromResult(StopResult ?? LauncherActionResult.Ok(_status));
        }
    }
}
