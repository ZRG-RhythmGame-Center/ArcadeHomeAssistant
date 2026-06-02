using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using MaimaiHomeAgent.Files;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace MaimaiHomeAgent.Tests.Files;

/// <summary>
/// Integration tests for <c>GET /api/file-roots</c> and <c>GET /api/files</c>.
/// Boots the real Program via <see cref="WebApplicationFactory{TEntryPoint}"/>
/// so the routing, DI, and JSON serializer pipeline match production exactly.
/// </summary>
[Collection("WafProgramTests")]
public sealed class FileListingEndpointsTests : IDisposable
{
    private readonly string _rootPath;
    private readonly TestAgentFactory _factory;
    private readonly HttpClient _client;

    public FileListingEndpointsTests()
    {
        // Per-test sandbox dir under TEMP. We populate this before WebApp builds
        // so the in-memory FileRoots config snapshot points at a real directory.
        _rootPath = Path.Combine(
            Path.GetTempPath(),
            "maimai-listing-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_rootPath);

        _factory = new TestAgentFactory(_rootPath);
        _client = _factory.CreateClient();
    }

    public void Dispose()
    {
        _client.Dispose();
        _factory.Dispose();
        try
        {
            if (Directory.Exists(_rootPath))
            {
                Directory.Delete(_rootPath, recursive: true);
            }
        }
        catch
        {
            // best-effort cleanup
        }
    }

    [Fact]
    public async Task GetFileRoots_ReturnsConfiguredRoots_WithoutDiskPath()
    {
        var response = await _client.GetAsync("/api/file-roots");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        var roots = await response.Content.ReadFromJsonAsync<List<JsonElement>>();
        Assert.NotNull(roots);
        Assert.Single(roots);

        var root = roots[0];
        Assert.Equal("test", root.GetProperty("id").GetString());
        Assert.Equal("Test Root", root.GetProperty("name").GetString());
        Assert.False(root.GetProperty("readOnly").GetBoolean());
        // Real disk path MUST NOT leak to clients.
        Assert.False(root.TryGetProperty("path", out _));
    }

    [Fact]
    public async Task GetFileRoots_StringArrayConfig_ReturnsUsableRoots()
    {
        using var factory = new StringArrayRootFactory(_rootPath);
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/api/file-roots");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var roots = await response.Content.ReadFromJsonAsync<List<JsonElement>>();
        Assert.NotNull(roots);
        var root = Assert.Single(roots);
        Assert.Equal(new DirectoryInfo(_rootPath).Name.ToLowerInvariant(), root.GetProperty("id").GetString());
        Assert.Equal(new DirectoryInfo(_rootPath).Name, root.GetProperty("name").GetString());
        Assert.False(root.GetProperty("readOnly").GetBoolean());
        Assert.False(root.TryGetProperty("path", out _));
    }

    [Fact]
    public async Task GetFileRoots_WildcardConfig_ReturnsReadyDriveRoots()
    {
        using var factory = new WildcardRootFactory();
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/api/file-roots");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var roots = await response.Content.ReadFromJsonAsync<List<JsonElement>>();
        Assert.NotNull(roots);
        Assert.NotEmpty(roots);
        foreach (var root in roots)
        {
            Assert.False(string.IsNullOrWhiteSpace(root.GetProperty("id").GetString()));
            Assert.False(string.IsNullOrWhiteSpace(root.GetProperty("name").GetString()));
            Assert.False(root.GetProperty("readOnly").GetBoolean());
            Assert.False(root.TryGetProperty("path", out _));
        }
    }

    [Fact]
    public async Task GetFiles_ValidPath_ReturnsEntriesAndShape()
    {
        // Seed: 1 dir + 2 files with deterministic content.
        Directory.CreateDirectory(Path.Combine(_rootPath, "subdir"));
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "alpha.txt"), "hello");
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "beta.txt"), "world!!");

        var response = await _client.GetAsync("/api/files?rootId=test&path=");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal(3, body.GetProperty("total").GetInt32());
        Assert.False(body.GetProperty("truncated").GetBoolean());

        var entries = body.GetProperty("entries").EnumerateArray().ToList();
        Assert.Equal(3, entries.Count);

        // Directory should sort first.
        Assert.Equal("subdir", entries[0].GetProperty("name").GetString());
        Assert.Equal("dir", entries[0].GetProperty("kind").GetString());
        // Directories have no size in the response.
        Assert.Equal(JsonValueKind.Null, entries[0].GetProperty("size").ValueKind);

        var alpha = entries[1];
        Assert.Equal("alpha.txt", alpha.GetProperty("name").GetString());
        Assert.Equal("file", alpha.GetProperty("kind").GetString());
        Assert.Equal(5, alpha.GetProperty("size").GetInt64());
        Assert.True(alpha.TryGetProperty("modified", out _));
    }

    [Fact]
    public async Task GetFiles_PathTraversal_Returns403WithOutsideRootError()
    {
        var response = await _client.GetAsync("/api/files?rootId=test&path=../../Windows");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("path_outside_root", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task GetFiles_NonExistentPath_Returns404()
    {
        var response = await _client.GetAsync("/api/files?rootId=test&path=does-not-exist");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("path_not_found", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task GetFiles_LimitClampedTo500_ReturnsTruncatedTrue()
    {
        // Seed 600 files. Filesystem ops on TEMP are fast enough that this
        // stays well under the xunit per-test timeout.
        for (var i = 0; i < 600; i++)
        {
            await File.WriteAllTextAsync(
                Path.Combine(_rootPath, $"file-{i:D4}.bin"),
                string.Empty);
        }

        var response = await _client.GetAsync("/api/files?rootId=test&path=&limit=500");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal(600, body.GetProperty("total").GetInt32());
        Assert.True(body.GetProperty("truncated").GetBoolean());
        Assert.Equal(500, body.GetProperty("entries").GetArrayLength());
    }

    [Fact]
    public async Task GetFiles_HiddenAndSystemEntries_AreFiltered()
    {
        var visiblePath = Path.Combine(_rootPath, "visible.txt");
        var hiddenPath = Path.Combine(_rootPath, "hidden.txt");
        await File.WriteAllTextAsync(visiblePath, "v");
        await File.WriteAllTextAsync(hiddenPath, "h");
        File.SetAttributes(hiddenPath, File.GetAttributes(hiddenPath) | FileAttributes.Hidden);

        var response = await _client.GetAsync("/api/files?rootId=test&path=");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal(1, body.GetProperty("total").GetInt32());
        var entries = body.GetProperty("entries").EnumerateArray().ToList();
        Assert.Single(entries);
        Assert.Equal("visible.txt", entries[0].GetProperty("name").GetString());
    }

    [Fact]
    public async Task GetFiles_UnknownRootId_Returns404()
    {
        var response = await _client.GetAsync("/api/files?rootId=ghost&path=");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("root_not_found", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task GetFiles_MissingRootId_Returns400()
    {
        var response = await _client.GetAsync("/api/files?path=");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("rootId_required", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task GetFiles_PathIsFile_Returns400()
    {
        var filePath = Path.Combine(_rootPath, "file.txt");
        await File.WriteAllTextAsync(filePath, "hi");

        var response = await _client.GetAsync("/api/files?rootId=test&path=file.txt");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("not_a_directory", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task GetFiles_OffsetPagination_ReturnsCorrectPage()
    {
        for (var i = 0; i < 50; i++)
        {
            await File.WriteAllTextAsync(
                Path.Combine(_rootPath, $"f-{i:D2}.txt"),
                string.Empty);
        }

        var response = await _client.GetAsync("/api/files?rootId=test&path=&offset=10&limit=5");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();

        Assert.Equal(50, body.GetProperty("total").GetInt32());
        Assert.True(body.GetProperty("truncated").GetBoolean());
        Assert.Equal(5, body.GetProperty("entries").GetArrayLength());

        // Files are sorted by name; offset=10 + 5 means f-10..f-14.
        var entries = body.GetProperty("entries").EnumerateArray().ToList();
        Assert.Equal("f-10.txt", entries[0].GetProperty("name").GetString());
        Assert.Equal("f-14.txt", entries[4].GetProperty("name").GetString());
    }

    /// <summary>
    /// Test host that injects a single FileRoot pointing at a per-test temp
    /// directory. WebApplicationFactory wires its own TestServer for
    /// minimal-API hosts; we just layer overrides via <c>ConfigureWebHost</c>.
    /// </summary>
    private sealed class TestAgentFactory(string rootPath) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Testing");

            builder.ConfigureAppConfiguration((_, config) =>
            {
                config.AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["FileRoots:0:Id"] = "test",
                    ["FileRoots:0:Name"] = "Test Root",
                    ["FileRoots:0:Path"] = rootPath,
                    ["FileRoots:0:ReadOnly"] = "false",
                });
            });

            // Strip background hosted services (mDNS, heartbeat, STA dispatcher)
            // so test runs don't open sockets or pin threads. Auth has been
            // removed from the agent so no token store stubbing is needed.
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

    private sealed class StringArrayRootFactory(string rootPath) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Testing");

            builder.ConfigureAppConfiguration((_, config) =>
            {
                config.AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["FileRoots:0"] = rootPath,
                });
            });

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

    private sealed class WildcardRootFactory : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseEnvironment("Testing");

            builder.ConfigureAppConfiguration((_, config) =>
            {
                config.AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["FileRoots:0"] = "*",
                });
            });

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
