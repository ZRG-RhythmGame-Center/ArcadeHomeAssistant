namespace MaimaiHomeAgent.Files;

/// <summary>
///     HTTP endpoints for read-only file management:
///     <list type="bullet">
///         <item><c>GET /api/file-roots</c> — list configured roots, sanitized for clients.</item>
///         <item><c>GET /api/files</c> — list one directory level under a root, with pagination.</item>
///     </list>
/// </summary>
/// <remarks>
///     All path resolution flows through <see cref="PathGuard.ResolveSafe" />; this
///     module never touches disk on a path it has not first validated. Real disk
///     paths are kept server-side — clients only see the root id and relative entry names.
/// </remarks>
public static class FileListingEndpoints
{
    /// <summary>Default page size when the caller doesn't specify <c>limit</c>.</summary>
    public const int DefaultLimit = 200;

    /// <summary>Hard cap on page size. Larger requests are clamped, not rejected.</summary>
    public const int MaxLimit = 500;

    public static IEndpointRouteBuilder MapFileListingEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        app.MapGet("/api/file-roots", (IFileRootService rootService) =>
        {
            // Project to DTOs so the on-disk Path is never serialized to clients.
            var roots = rootService.ListRoots()
                .Select(r => new FileRootDto(r.Id, r.Name, r.ReadOnly))
                .ToList();
            return Results.Ok(roots);
        });

        app.MapGet("/api/files", (
            string? rootId,
            string? path,
            int? offset,
            int? limit,
            IFileRootService rootService) =>
        {
            if (string.IsNullOrEmpty(rootId)) return Results.BadRequest(new { error = "rootId_required" });

            var root = rootService.FindById(rootId);
            if (root is null) return Results.NotFound(new { error = "root_not_found" });

            var relative = path ?? string.Empty;
            var guard = PathGuard.ResolveSafe(root, relative);
            if (!guard.IsOk)
                return Results.Json(
                    new { error = MapError(guard.Error!.Value) },
                    statusCode: StatusCodes.Status403Forbidden);

            var resolved = guard.ResolvedPath!;

            // ResolveSafe succeeds even for non-existent paths (PathGuard validates
            // shape, not existence). Distinguish missing vs file-not-dir here.
            if (!Directory.Exists(resolved))
            {
                if (File.Exists(resolved)) return Results.BadRequest(new { error = "not_a_directory" });
                return Results.NotFound(new { error = "path_not_found" });
            }

            var actualOffset = Math.Max(0, offset ?? 0);
            var actualLimit = Math.Clamp(limit ?? DefaultLimit, 1, MaxLimit);

            var dir = new DirectoryInfo(resolved);

            // Materialize visible entries first so we have an authoritative total
            // count for the client — they can't paginate sensibly without it.
            var visible = new List<FileSystemInfo>();
            foreach (var info in dir.EnumerateFileSystemInfos())
            {
                if ((info.Attributes & (FileAttributes.Hidden | FileAttributes.System)) != 0) continue;
                visible.Add(info);
            }

            // Stable ordering: directories before files, then by name (case-insensitive).
            visible.Sort((a, b) =>
            {
                var aDir = (a.Attributes & FileAttributes.Directory) != 0;
                var bDir = (b.Attributes & FileAttributes.Directory) != 0;
                if (aDir != bDir) return aDir ? -1 : 1;
                return string.Compare(a.Name, b.Name, StringComparison.OrdinalIgnoreCase);
            });

            var total = visible.Count;
            var page = visible
                .Skip(actualOffset)
                .Take(actualLimit)
                .Select(static info => new FileEntryDto(
                    info.Name,
                    (info.Attributes & FileAttributes.Directory) != 0 ? "dir" : "file",
                    info is FileInfo fi ? fi.Length : null,
                    info.LastWriteTimeUtc))
                .ToList();

            // truncated = there are more entries beyond what we returned in this page.
            var truncated = total > actualOffset + page.Count;

            return Results.Ok(new FileListingResponse(
                page,
                total,
                truncated));
        });

        return app;
    }

    /// <summary>
    ///     Map <see cref="PathSafetyError" /> values to stable snake_case strings for
    ///     API consumers. Keeping these as a closed set avoids leaking internal enum
    ///     names if we ever rename the enum.
    /// </summary>
    private static string MapError(PathSafetyError err)
    {
        return err switch
        {
            PathSafetyError.OutsideRoot => "path_outside_root",
            PathSafetyError.Absolute => "path_absolute",
            PathSafetyError.InvalidChar => "path_invalid_char",
            PathSafetyError.SymlinkEscape => "symlink_escape",
            PathSafetyError.ReparsePointInPath => "reparse_point_in_path",
            _ => "path_invalid"
        };
    }
}

/// <summary>Public-facing root descriptor — never includes the on-disk path.</summary>
public sealed record FileRootDto(string Id, string Name, bool ReadOnly);

/// <summary>One directory entry in a listing response.</summary>
public sealed record FileEntryDto(string Name, string Kind, long? Size, DateTime Modified);

/// <summary>Paginated directory-listing response.</summary>
public sealed record FileListingResponse(
    IReadOnlyList<FileEntryDto> Entries,
    int Total,
    bool Truncated);