using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Http.HttpResults;

namespace MaimaiHomeAgent.Files;

/// <summary>
///     Endpoints for reading and updating file root configuration.
///     GET /api/config returns the full configuration (discovery, fileRoots, listenAddress).
///     PUT /api/config/file-roots updates the file roots list with validation and persistence.
/// </summary>
public static class FileRootsConfigEndpoints
{
    private const string ConfigFileName = "appsettings.user.json";

    public static void MapFileRootsConfigEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/api/config", GetConfig)
            .WithName("GetConfig")
            .WithOpenApi();

        app.MapPut("/api/config/file-roots", PutFileRoots)
            .WithName("PutFileRoots")
            .WithOpenApi();
    }

    private static Ok<ConfigResponse> GetConfig(
        IFileRootService fileRootService,
        IConfiguration configuration)
    {
        var roots = fileRootService.ListRoots();
        var fileRootsDto = roots.Select(r => new FileRootDto(r.Id, r.Name, r.ReadOnly)).ToList();

        var response = new ConfigResponse(
            configuration.GetSection("Discovery").Get<object>() ?? new { },
            fileRootsDto,
            "http://0.0.0.0:8765"
        );

        return TypedResults.Ok(response);
    }

    private static async Task<IResult> PutFileRoots(
        IFileRootService fileRootService,
        IConfiguration configuration,
        ILoggerFactory loggerFactory,
        HttpContext context)
    {
        var logger = loggerFactory.CreateLogger("FileRootsConfigEndpoints");
        try
        {
            var payload = await context.Request.ReadFromJsonAsync<FileRootUpdateDto[]>();
            if (payload == null || payload.Length == 0)
                return TypedResults.BadRequest("File roots list cannot be empty");

            // Validate: check for duplicate IDs
            var ids = new HashSet<string>();
            foreach (var item in payload)
                if (!ids.Add(item.Id ?? string.Empty))
                    return TypedResults.BadRequest($"Duplicate file root ID: {item.Id}");

            // Validate: check that all paths exist and are directories
            foreach (var item in payload)
            {
                if (string.IsNullOrWhiteSpace(item.Path))
                    return TypedResults.BadRequest("File root path cannot be empty");

                var expandedPath = Environment.ExpandEnvironmentVariables(item.Path);
                if (!Directory.Exists(expandedPath))
                    return TypedResults.BadRequest($"Path does not exist or is not a directory: {item.Path}");
            }

            // Convert to FileRoot records
            var newRoots = payload.Select(dto => new FileRoot(
                dto.Id ?? string.Empty,
                dto.Name ?? dto.Id ?? string.Empty,
                dto.Path ?? string.Empty,
                dto.ReadOnly
            )).ToList();

            // Persist to appsettings.user.json using atomic write
            await PersistFileRootsAsync(newRoots, configuration, logger);

            // Hot-reload in memory
            fileRootService.Reload(newRoots);

            // Return updated config
            var fileRootsDto = newRoots.Select(r => new FileRootDto(r.Id, r.Name, r.ReadOnly)).ToList();
            var response = new ConfigResponse(
                configuration.GetSection("Discovery").Get<object>() ?? new { },
                fileRootsDto,
                "http://0.0.0.0:8765"
            );

            return TypedResults.Ok(response);
        }
        catch (JsonException ex)
        {
            logger.LogWarning(ex, "Invalid JSON in PUT /api/config/file-roots");
            return TypedResults.BadRequest("Invalid JSON format");
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Unexpected error in PUT /api/config/file-roots");
            return TypedResults.StatusCode(StatusCodes.Status500InternalServerError);
        }
    }

    private static async Task PersistFileRootsAsync(
        IEnumerable<FileRoot> roots,
        IConfiguration configuration,
        ILogger logger)
    {
        // Determine the directory where appsettings.json lives
        // For the running assembly, this is typically the app's base directory
        var appSettingsPath = Path.Combine(AppContext.BaseDirectory, "appsettings.json");
        var appSettingsDir = Path.GetDirectoryName(appSettingsPath) ?? AppContext.BaseDirectory;
        var userConfigPath = Path.Combine(appSettingsDir, ConfigFileName);
        var tempPath = userConfigPath + ".tmp";

        try
        {
            // Build the config object to persist
            var configToSave = new
            {
                FileRoots = roots.Select(r => new
                {
                    r.Id,
                    r.Name,
                    r.Path,
                    r.ReadOnly
                }).ToList()
            };

            var options = new JsonSerializerOptions
            {
                WriteIndented = true,
                DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
            };

            var json = JsonSerializer.Serialize(configToSave, options);

            // Atomic write: write to temp file, then move with overwrite
            await File.WriteAllTextAsync(tempPath, json);
            File.Move(tempPath, userConfigPath, true);

            logger.LogInformation(
                "Persisted {Count} file root(s) to {Path}",
                roots.Count(),
                userConfigPath);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to persist file roots to {Path}", userConfigPath);
            // Clean up temp file if it exists
            try
            {
                if (File.Exists(tempPath)) File.Delete(tempPath);
            }
            catch
            {
                // Ignore cleanup errors
            }

            throw;
        }
    }

    private sealed record ConfigResponse(
        [property: JsonPropertyName("discovery")]
        object Discovery,
        [property: JsonPropertyName("fileRoots")]
        List<FileRootDto> FileRoots,
        [property: JsonPropertyName("listenAddress")]
        string ListenAddress
    );

    private sealed record FileRootDto(
        [property: JsonPropertyName("id")] string Id,
        [property: JsonPropertyName("name")] string Name,
        [property: JsonPropertyName("readOnly")]
        bool ReadOnly
    );

    private sealed record FileRootUpdateDto(
        [property: JsonPropertyName("id")] string? Id,
        [property: JsonPropertyName("name")] string? Name,
        [property: JsonPropertyName("path")] string? Path,
        [property: JsonPropertyName("readOnly")]
        bool ReadOnly
    );
}