using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;

namespace MaimaiHomeAgent.Tests.Files.Mutation;

/// <summary>
///     Integration tests for the file mutation endpoints (upload / download /
///     delete / rename / move). Boots the real <c>Program</c> through
///     <see cref="WebApplicationFactory{TEntryPoint}" /> so request size limits,
///     JSON binding, and the antiforgery toggles all match production.
/// </summary>
[Collection("WafProgramTests")]
public sealed class FileMutationEndpointsTests : IDisposable
{
    private readonly HttpClient _client;
    private readonly TestAgentFactory _factory;
    private readonly string _readOnlyRootPath;
    private readonly string _rootPath;

    public FileMutationEndpointsTests()
    {
        _rootPath = Path.Combine(
            Path.GetTempPath(),
            "maimai-mutation-tests-" + Guid.NewGuid().ToString("N"));
        _readOnlyRootPath = Path.Combine(
            Path.GetTempPath(),
            "maimai-mutation-ro-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_rootPath);
        Directory.CreateDirectory(_readOnlyRootPath);

        _factory = new TestAgentFactory(_rootPath, _readOnlyRootPath);

        // Disable HttpClient's default 100s timeout for the 105MB upload test —
        // even on TestServer, pushing 100+ MB through the in-memory pipeline
        // can take several seconds on slower disks.
        _client = _factory.CreateClient();
        _client.Timeout = TimeSpan.FromMinutes(5);
    }

    public void Dispose()
    {
        _client.Dispose();
        _factory.Dispose();
        TryDelete(_rootPath);
        TryDelete(_readOnlyRootPath);
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (Directory.Exists(path)) Directory.Delete(path, true);
        }
        catch
        {
            // best-effort cleanup
        }
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    [Fact]
    public async Task Upload_Success_Returns201()
    {
        using var content = BuildUploadForm("test", "hello.txt", Encoding.UTF8.GetBytes("hello world"), false);

        var response = await _client.PostAsync("/api/files/upload", content);

        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("test", body.GetProperty("rootId").GetString());
        Assert.Equal("hello.txt", body.GetProperty("path").GetString());
        Assert.Equal(11, body.GetProperty("size").GetInt64());
        Assert.False(body.GetProperty("overwritten").GetBoolean());

        var diskPath = Path.Combine(_rootPath, "hello.txt");
        Assert.True(File.Exists(diskPath));
        Assert.Equal("hello world", await File.ReadAllTextAsync(diskPath));
    }

    [Fact]
    public async Task Upload_OverwriteFalse_AndFileExists_Returns409()
    {
        var existing = Path.Combine(_rootPath, "existing.txt");
        await File.WriteAllTextAsync(existing, "original");

        using var content = BuildUploadForm("test", "existing.txt", Encoding.UTF8.GetBytes("replacement"), false);

        var response = await _client.PostAsync("/api/files/upload", content);

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("file_exists", body.GetProperty("error").GetString());

        // Original content must be preserved.
        Assert.Equal("original", await File.ReadAllTextAsync(existing));
    }

    [Fact]
    public async Task Upload_OverwriteTrue_AndFileExists_Returns200()
    {
        var existing = Path.Combine(_rootPath, "to-overwrite.txt");
        await File.WriteAllTextAsync(existing, "old");

        using var content = BuildUploadForm("test", "to-overwrite.txt", Encoding.UTF8.GetBytes("new bytes"), true);

        var response = await _client.PostAsync("/api/files/upload", content);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.True(body.GetProperty("overwritten").GetBoolean());
        Assert.Equal("new bytes", await File.ReadAllTextAsync(existing));
    }

    [Fact]
    public async Task Upload_LargerThan100Mb_Returns413()
    {
        // 105 MiB triggers the explicit Content-Length check before the form
        // reader allocates anything. Allocating once on 64-bit is fine.
        var bytes = new byte[105L * 1024 * 1024];

        using var content = BuildUploadForm("test", "huge.bin", bytes, false);

        var response = await _client.PostAsync("/api/files/upload", content);

        Assert.Equal((HttpStatusCode)413, response.StatusCode);

        // File must NOT have been written.
        Assert.False(File.Exists(Path.Combine(_rootPath, "huge.bin")));
    }

    [Fact]
    public async Task Upload_PathTraversal_Returns403()
    {
        using var content = BuildUploadForm("test", "../escape.txt", Encoding.UTF8.GetBytes("nope"), false);

        var response = await _client.PostAsync("/api/files/upload", content);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("path_outside_root", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task Upload_ReadOnlyRoot_Returns403()
    {
        using var content = BuildUploadForm("ro", "x.txt", Encoding.UTF8.GetBytes("nope"), false);

        var response = await _client.PostAsync("/api/files/upload", content);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("read_only_root", body.GetProperty("error").GetString());
    }

    // -------------------------------------------------------------------------
    // Download
    // -------------------------------------------------------------------------

    [Fact]
    public async Task Download_ExistingFile_ReturnsBytes()
    {
        var diskPath = Path.Combine(_rootPath, "download.txt");
        var payload = "the quick brown fox";
        await File.WriteAllTextAsync(diskPath, payload);

        var response = await _client.GetAsync("/api/files/download?rootId=test&path=download.txt");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await response.Content.ReadAsStringAsync();
        Assert.Equal(payload, body);
    }

    [Fact]
    public async Task Download_NonExistent_Returns404()
    {
        var response = await _client.GetAsync("/api/files/download?rootId=test&path=missing.txt");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("path_not_found", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task Download_PathTraversal_Returns403()
    {
        var response = await _client.GetAsync("/api/files/download?rootId=test&path=../../Windows/System32/cmd.exe");

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    [Fact]
    public async Task Delete_ConfirmMissing_Returns400()
    {
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "del.txt"), "x");

        // Note: confirm field omitted entirely.
        var response = await SendJsonAsync(HttpMethod.Delete, "/api/files", new
        {
            rootId = "test",
            path = "del.txt"
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("confirm_required", body.GetProperty("error").GetString());
        Assert.True(File.Exists(Path.Combine(_rootPath, "del.txt")));
    }

    [Fact]
    public async Task Delete_ConfirmFalse_Returns400()
    {
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "del.txt"), "x");

        var response = await SendJsonAsync(HttpMethod.Delete, "/api/files", new
        {
            rootId = "test",
            path = "del.txt",
            confirm = false
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("confirm_required", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task Delete_Directory_Returns400()
    {
        var dir = Path.Combine(_rootPath, "subdir");
        Directory.CreateDirectory(dir);

        var response = await SendJsonAsync(HttpMethod.Delete, "/api/files", new
        {
            rootId = "test",
            path = "subdir",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("directory_delete_unsupported", body.GetProperty("error").GetString());
        Assert.True(Directory.Exists(dir));
    }

    [Fact]
    public async Task Delete_File_Returns200()
    {
        var disk = Path.Combine(_rootPath, "doomed.txt");
        await File.WriteAllTextAsync(disk, "bye");

        var response = await SendJsonAsync(HttpMethod.Delete, "/api/files", new
        {
            rootId = "test",
            path = "doomed.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.False(File.Exists(disk));
    }

    [Fact]
    public async Task Delete_ReadOnlyRoot_Returns403()
    {
        var disk = Path.Combine(_readOnlyRootPath, "ro-file.txt");
        await File.WriteAllTextAsync(disk, "x");

        var response = await SendJsonAsync(HttpMethod.Delete, "/api/files", new
        {
            rootId = "ro",
            path = "ro-file.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("read_only_root", body.GetProperty("error").GetString());
        Assert.True(File.Exists(disk));
    }

    // -------------------------------------------------------------------------
    // Rename
    // -------------------------------------------------------------------------

    [Fact]
    public async Task Rename_HappyPath_Returns200()
    {
        var oldPath = Path.Combine(_rootPath, "before.txt");
        await File.WriteAllTextAsync(oldPath, "hi");

        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/rename", new
        {
            rootId = "test",
            path = "before.txt",
            newName = "after.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.False(File.Exists(oldPath));
        Assert.True(File.Exists(Path.Combine(_rootPath, "after.txt")));
    }

    [Fact]
    public async Task Rename_TargetExists_Returns409()
    {
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "src.txt"), "src");
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "dst.txt"), "dst");

        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/rename", new
        {
            rootId = "test",
            path = "src.txt",
            newName = "dst.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("file_exists", body.GetProperty("error").GetString());
        // Both files still exist with original content.
        Assert.Equal("src", await File.ReadAllTextAsync(Path.Combine(_rootPath, "src.txt")));
        Assert.Equal("dst", await File.ReadAllTextAsync(Path.Combine(_rootPath, "dst.txt")));
    }

    [Fact]
    public async Task Rename_NonExistent_Returns404()
    {
        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/rename", new
        {
            rootId = "test",
            path = "ghost.txt",
            newName = "renamed.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("path_not_found", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task Rename_NewNameWithPathSeparator_Returns400()
    {
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "ok.txt"), "x");

        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/rename", new
        {
            rootId = "test",
            path = "ok.txt",
            newName = "subdir/evil.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("invalid_name", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task Rename_ConfirmMissing_Returns400()
    {
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "ok.txt"), "x");

        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/rename", new
        {
            rootId = "test",
            path = "ok.txt",
            newName = "ok2.txt"
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("confirm_required", body.GetProperty("error").GetString());
    }

    // -------------------------------------------------------------------------
    // Move
    // -------------------------------------------------------------------------

    [Fact]
    public async Task Move_SameVolume_Returns200()
    {
        Directory.CreateDirectory(Path.Combine(_rootPath, "dst"));
        var src = Path.Combine(_rootPath, "src.txt");
        await File.WriteAllTextAsync(src, "moved");

        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/move", new
        {
            rootId = "test",
            fromPath = "src.txt",
            toPath = "dst/src.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.False(File.Exists(src));
        Assert.True(File.Exists(Path.Combine(_rootPath, "dst", "src.txt")));
    }

    [Fact]
    public async Task Move_CrossRootAttempt_PathEscapesRoot_Returns403()
    {
        // The API only accepts a single rootId — there are no fromRootId /
        // toRootId fields, so cross-root is structurally impossible. The
        // closest "cross-root attempt" a client can make is a relative path
        // that climbs above the configured root; PathGuard rejects with 403.
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "stay.txt"), "x");

        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/move", new
        {
            rootId = "test",
            fromPath = "stay.txt",
            toPath = "../escape.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("path_outside_root", body.GetProperty("error").GetString());
        Assert.True(File.Exists(Path.Combine(_rootPath, "stay.txt")));
    }

    [Fact]
    public async Task Move_ConfirmMissing_Returns400()
    {
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "x.txt"), "x");

        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/move", new
        {
            rootId = "test",
            fromPath = "x.txt",
            toPath = "y.txt"
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("confirm_required", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task Move_TargetExists_OverwriteFalse_Returns409()
    {
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "src.txt"), "src");
        await File.WriteAllTextAsync(Path.Combine(_rootPath, "dst.txt"), "dst");

        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/move", new
        {
            rootId = "test",
            fromPath = "src.txt",
            toPath = "dst.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("file_exists", body.GetProperty("error").GetString());
    }

    [Fact]
    public async Task Move_ReadOnlyRoot_Returns403()
    {
        await File.WriteAllTextAsync(Path.Combine(_readOnlyRootPath, "x.txt"), "x");

        var response = await SendJsonAsync(HttpMethod.Post, "/api/files/move", new
        {
            rootId = "ro",
            fromPath = "x.txt",
            toPath = "y.txt",
            confirm = true
        });

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.Equal("read_only_root", body.GetProperty("error").GetString());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MultipartFormDataContent BuildUploadForm(string rootId, string path, byte[] fileBytes,
        bool overwrite)
    {
        // The implementation parses overwrite by case-insensitive string match
        // on "true". Send the value as a plain form field rather than relying
        // on JSON binding, since this endpoint is multipart-only.
        var content = new MultipartFormDataContent("----maimai-test-boundary");
        content.Add(new StringContent(rootId), "rootId");
        content.Add(new StringContent(path), "path");
        content.Add(new StringContent(overwrite ? "true" : "false"), "overwrite");

        var fileContent = new ByteArrayContent(fileBytes);
        fileContent.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        content.Add(fileContent, "file", Path.GetFileName(path));
        return content;
    }

    private async Task<HttpResponseMessage> SendJsonAsync(HttpMethod method, string url, object body)
    {
        var request = new HttpRequestMessage(method, url)
        {
            Content = JsonContent.Create(body)
        };
        return await _client.SendAsync(request);
    }

    /// <summary>
    ///     WAF host with two roots: <c>test</c> (writable) and <c>ro</c>
    ///     (read-only) plus all hosted services stripped so tests don't open
    ///     sockets or pin STA threads.
    /// </summary>
    private sealed class TestAgentFactory(string rootPath, string readOnlyRootPath) : WebApplicationFactory<Program>
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

                    ["FileRoots:1:Id"] = "ro",
                    ["FileRoots:1:Name"] = "Read-Only Root",
                    ["FileRoots:1:Path"] = readOnlyRootPath,
                    ["FileRoots:1:ReadOnly"] = "true"
                });
            });

            builder.ConfigureServices(services =>
            {
                var hosted = services
                    .Where(d => d.ServiceType == typeof(IHostedService))
                    .ToList();
                foreach (var d in hosted) services.Remove(d);
            });
        }
    }
}