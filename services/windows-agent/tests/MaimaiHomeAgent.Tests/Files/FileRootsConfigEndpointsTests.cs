using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MaimaiHomeAgent.Files;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace MaimaiHomeAgent.Tests.Files;

public class FileRootsConfigEndpointsTests : IAsyncLifetime
{
    private WebApplication _app = null!;
    private HttpClient _client = null!;
    private IFileRootService _fileRootService = null!;
    private string _tempDir = null!;

    public async Task InitializeAsync()
    {
        _tempDir = Path.Combine(Path.GetTempPath(), $"maimai-test-{Guid.NewGuid():N}");
        Directory.CreateDirectory(_tempDir);

        var builder = WebApplication.CreateBuilder();
        builder.Services.AddSingleton<IFileRootService, FileRootService>();
        builder.WebHost.UseTestServer();

        _app = builder.Build();
        _fileRootService = _app.Services.GetRequiredService<IFileRootService>();

        _app.MapFileRootsConfigEndpoints();

        await _app.StartAsync();
        _client = _app.GetTestClient();
    }

    public async Task DisposeAsync()
    {
        await _app.StopAsync();
        await _app.DisposeAsync();
        _client.Dispose();

        if (Directory.Exists(_tempDir))
        {
            Directory.Delete(_tempDir, recursive: true);
        }
    }

    [Fact]
    public async Task GetConfig_Returns200WithExpectedShape()
    {
        // Act
        var response = await _client.GetAsync("/api/config");

        // Assert
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var content = await response.Content.ReadAsStringAsync();
        var json = JsonDocument.Parse(content);
        var root = json.RootElement;

        // Verify shape: { discovery: {...}, fileRoots: [...], listenAddress: "..." }
        Assert.True(root.TryGetProperty("discovery", out _), "Missing 'discovery' property");
        Assert.True(root.TryGetProperty("fileRoots", out var fileRootsElem), "Missing 'fileRoots' property");
        Assert.True(root.TryGetProperty("listenAddress", out var listenElem), "Missing 'listenAddress' property");

        Assert.Equal("http://0.0.0.0:8765", listenElem.GetString());
        Assert.Equal(JsonValueKind.Array, fileRootsElem.ValueKind);
    }

    [Fact]
    public async Task PutFileRoots_WithDuplicateId_Returns400()
    {
        // Arrange
        var dir1 = Path.Combine(_tempDir, "dir1");
        Directory.CreateDirectory(dir1);

        var payload = new[]
        {
            new { id = "root1", name = "Root 1", path = dir1, readOnly = false },
            new { id = "root1", name = "Root 2", path = dir1, readOnly = false }
        };

        // Act
        var response = await _client.PutAsJsonAsync("/api/config/file-roots", payload);

        // Assert
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task PutFileRoots_WithNonExistentPath_Returns400()
    {
        // Arrange
        var nonExistentPath = Path.Combine(_tempDir, "does-not-exist");

        var payload = new[]
        {
            new { id = "root1", name = "Root 1", path = nonExistentPath, readOnly = false }
        };

        // Act
        var response = await _client.PutAsJsonAsync("/api/config/file-roots", payload);

        // Assert
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task PutFileRoots_WithValidData_Returns200AndPersists()
    {
        // Arrange
        var dir1 = Path.Combine(_tempDir, "dir1");
        var dir2 = Path.Combine(_tempDir, "dir2");
        Directory.CreateDirectory(dir1);
        Directory.CreateDirectory(dir2);

        var payload = new[]
        {
            new { id = "root1", name = "Root 1", path = dir1, readOnly = false },
            new { id = "root2", name = "Root 2", path = dir2, readOnly = true }
        };

        // Act
        var response = await _client.PutAsJsonAsync("/api/config/file-roots", payload);

        // Assert
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        // Verify response contains the updated roots (without disk paths)
        var content = await response.Content.ReadAsStringAsync();
        var json = JsonDocument.Parse(content);
        var fileRoots = json.RootElement.GetProperty("fileRoots");
        Assert.Equal(2, fileRoots.GetArrayLength());

        // Verify in-memory state was reloaded
        var roots = _fileRootService.ListRoots();
        Assert.Equal(2, roots.Count);
        Assert.Equal("root1", roots[0].Id);
        Assert.Equal("root2", roots[1].Id);
    }
}
