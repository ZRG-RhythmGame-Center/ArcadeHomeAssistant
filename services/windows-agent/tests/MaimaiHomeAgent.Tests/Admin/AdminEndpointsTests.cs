using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MaimaiHomeAgent.Admin;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;

namespace MaimaiHomeAgent.Tests.Admin;

public sealed class AdminEndpointsTests : IAsyncLifetime
{
    private WebApplication _app = null!;
    private HttpClient _client = null!;

    public async Task InitializeAsync()
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseTestServer();
        builder.Services.Configure<AdminOptions>(options => options.Password = "seganmsl");
        builder.Services.AddSingleton<AdminGuard>();

        _app = builder.Build();
        _app.MapAdminEndpoints();
        await _app.StartAsync();
        _client = _app.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        await _app.DisposeAsync();
        _client.Dispose();
    }

    [Fact]
    public async Task Login_WithCorrectPassword_ReturnsOk()
    {
        var response = await _client.PostAsJsonAsync("/api/admin/session", new { password = "seganmsl" });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.True(doc.RootElement.GetProperty("authenticated").GetBoolean());
    }

    [Fact]
    public async Task Login_WithWrongPassword_Returns401()
    {
        var response = await _client.PostAsJsonAsync("/api/admin/session", new { password = "wrong" });

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        Assert.Equal("admin_unauthorized", doc.RootElement.GetProperty("error").GetString());
    }
}