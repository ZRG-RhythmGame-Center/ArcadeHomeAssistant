using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using MaimaiHomeAgent.Admin;
using MaimaiHomeAgent.Settings;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace MaimaiHomeAgent.Tests.Settings;

public sealed class SettingsEndpointsTests : IAsyncLifetime
{
    private WebApplication _app = null!;
    private HttpClient _client = null!;
    private FakeSettingsService _settings = null!;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.Configure<AdminOptions>(options => options.Password = "seganmsl");
        builder.Services.AddSingleton<AdminGuard>();
        _settings = new FakeSettingsService();
        builder.Services.AddSingleton<IAgentSettingsService>(_settings);

        _app = builder.Build();
        _app.MapSettingsEndpoints();
        await _app.StartAsync();
        _client = _app.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        await _app.DisposeAsync();
        _client.Dispose();
    }

    [Fact]
    public async Task GetSettings_WithoutAdminPassword_Returns401()
    {
        var response = await _client.GetAsync("/api/settings");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task GetSettings_WithAdminPassword_ReturnsSnapshot()
    {
        Authorize();

        var response = await _client.GetAsync("/api/settings");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.True(doc.RootElement.GetProperty("adminPasswordConfigured").GetBoolean());
    }

    [Fact]
    public async Task PutSettings_WhenValidationFails_Returns400()
    {
        Authorize();
        _settings.NextResult = SettingsUpdateResult.Failed(new[]
        {
            new SettingsValidationError("launcher_item_name_required", "名称不能为空")
        });

        var response = await _client.PutAsJsonAsync("/api/settings", new { });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("settings_validation_failed", doc.RootElement.GetProperty("error").GetString());
    }

    [Fact]
    public async Task PutSettings_WhenDuplicateFails_Returns409()
    {
        Authorize();
        _settings.NextResult = SettingsUpdateResult.Failed(new[]
        {
            new SettingsValidationError("file_root_id_duplicate", "重复")
        });

        var response = await _client.PutAsJsonAsync("/api/settings", new { });

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
    }

    private void Authorize()
    {
        _client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", "seganmsl");
    }

    private sealed class FakeSettingsService : IAgentSettingsService
    {
        private readonly AgentSettingsSnapshot _settings = new(
            true,
            false,
            new LauncherSettingsDto(false, 1080, 1920, null, "Left", "Right", "Enter", "F11",
                Array.Empty<LauncherItemSettingsDto>()),
            Array.Empty<FileRootSettingsDto>(),
            new RemoteShutdownSettingsDto(false, null));

        public SettingsUpdateResult? NextResult { get; set; }

        public Task<AgentSettingsSnapshot> GetAsync(CancellationToken ct = default)
        {
            return Task.FromResult(_settings);
        }

        public Task<SettingsUpdateResult> UpdateAsync(AgentSettingsUpdateRequest request,
            CancellationToken ct = default)
        {
            return Task.FromResult(NextResult ?? SettingsUpdateResult.Ok(_settings));
        }
    }
}
