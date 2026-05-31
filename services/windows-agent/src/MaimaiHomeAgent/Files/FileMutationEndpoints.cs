using System.Text.Json;
using MaimaiHomeAgent.Realtime;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.Mvc;

namespace MaimaiHomeAgent.Files;

/// <summary>
/// HTTP endpoints for mutating files under a configured <see cref="FileRoot"/>:
/// <list type="bullet">
///   <item><c>POST   /api/files/upload</c> — multipart upload (max 100 MiB).</item>
///   <item><c>GET    /api/files/download</c> — stream a file back to the client.</item>
///   <item><c>DELETE /api/files</c> — delete a single file (no recursive dir delete).</item>
///   <item><c>POST   /api/files/rename</c> — rename a file in place.</item>
///   <item><c>POST   /api/files/move</c> — move a file within the same root and volume.</item>
/// </list>
/// </summary>
/// <remarks>
/// Hard contract:
/// <list type="bullet">
///   <item>Every path goes through <see cref="PathGuard.ResolveSafe"/>; PathGuard failure → 403.</item>
///   <item><see cref="FileRoot.ReadOnly"/> roots reject any write with 403 <c>read_only_root</c>.</item>
///   <item>DELETE / rename / move require <c>confirm:true</c> in the body or 400 <c>confirm_required</c>.</item>
///   <item>Overwrite of an existing target requires <c>overwrite:true</c>; otherwise 409 <c>file_exists</c>.</item>
///   <item>Successful mutations broadcast a <see cref="EventEnvelope"/> via <see cref="EventHub"/>.</item>
/// </list>
/// </remarks>
public static class FileMutationEndpoints
{
    /// <summary>Hard cap on a single multipart upload body (100 MiB).</summary>
    public const long MaxUploadSize = 100L * 1024 * 1024;

    // newName must be a single filename component — no separators, no Windows reserved chars.
    private static readonly char[] InvalidNameChars =
        ['<', '>', ':', '"', '|', '?', '*', '/', '\\'];

    public static IEndpointRouteBuilder MapFileMutationEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        // ---------- Upload ----------------------------------------------------
        app.MapPost("/api/files/upload", async (
                HttpContext ctx,
                IFileRootService rootService,
                EventHub hub) =>
            {
                // Raise per-request body size to 100 MiB. Kestrel's default 30 MiB
                // would otherwise terminate the request well before our 413 check.
                var sizeFeature = ctx.Features.Get<IHttpMaxRequestBodySizeFeature>();
                if (sizeFeature is { IsReadOnly: false })
                {
                    sizeFeature.MaxRequestBodySize = MaxUploadSize;
                }

                // Reject early on Content-Length without buffering the body.
                if (ctx.Request.ContentLength is { } cl && cl > MaxUploadSize)
                {
                    return PayloadTooLargeResult();
                }

                if (!ctx.Request.HasFormContentType)
                {
                    return Results.BadRequest(new { error = "multipart_required" });
                }

                IFormCollection form;
                try
                {
                    form = await ctx.Request.ReadFormAsync().ConfigureAwait(false);
                }
                catch (BadHttpRequestException ex) when (ex.StatusCode == StatusCodes.Status413PayloadTooLarge)
                {
                    return PayloadTooLargeResult();
                }

                var rootId = form["rootId"].ToString();
                var path = form["path"].ToString();
                var overwriteRaw = form["overwrite"].ToString();
                var overwrite = string.Equals(overwriteRaw, "true", StringComparison.OrdinalIgnoreCase);
                var file = form.Files.GetFile("file");

                if (string.IsNullOrEmpty(rootId))
                {
                    return Results.BadRequest(new { error = "rootId_required" });
                }
                if (string.IsNullOrEmpty(path))
                {
                    return Results.BadRequest(new { error = "path_required" });
                }
                if (file is null)
                {
                    return Results.BadRequest(new { error = "file_required" });
                }
                if (file.Length > MaxUploadSize)
                {
                    return PayloadTooLargeResult();
                }

                var root = rootService.FindById(rootId);
                if (root is null)
                {
                    return Results.NotFound(new { error = "root_not_found" });
                }
                if (root.ReadOnly)
                {
                    return ForbiddenResult("read_only_root");
                }

                var guard = PathGuard.ResolveSafe(root, path);
                if (!guard.IsOk)
                {
                    return ForbiddenResult(MapPathError(guard.Error!.Value));
                }

                var resolved = guard.ResolvedPath!;

                if (Directory.Exists(resolved))
                {
                    return Results.BadRequest(new { error = "path_is_directory" });
                }

                var existed = File.Exists(resolved);
                if (existed && !overwrite)
                {
                    return Results.Json(new { error = "file_exists" }, statusCode: StatusCodes.Status409Conflict);
                }

                var parent = Path.GetDirectoryName(resolved);
                if (parent is not null && !Directory.Exists(parent))
                {
                    return Results.NotFound(new { error = "parent_not_found" });
                }

                // Stream straight to disk so we never buffer the whole file in memory.
                await using (var fileStream = new FileStream(
                    resolved,
                    FileMode.Create,
                    FileAccess.Write,
                    FileShare.None))
                {
                    await file.CopyToAsync(fileStream).ConfigureAwait(false);
                }

                await BroadcastFileEventAsync(hub, EventTypes.FileCreated, new { rootId, path, overwritten = existed })
                    .ConfigureAwait(false);

                return existed
                    ? Results.Ok(new { rootId, path, size = file.Length, overwritten = true })
                    : Results.Created(
                        $"/api/files/download?rootId={Uri.EscapeDataString(rootId)}&path={Uri.EscapeDataString(path)}",
                        new { rootId, path, size = file.Length, overwritten = false });
            })
            .DisableAntiforgery();

        // ---------- Download --------------------------------------------------
        app.MapGet("/api/files/download", (string? rootId, string? path, IFileRootService rootService) =>
        {
            if (string.IsNullOrEmpty(rootId))
            {
                return Results.BadRequest(new { error = "rootId_required" });
            }
            if (path is null)
            {
                return Results.BadRequest(new { error = "path_required" });
            }

            var root = rootService.FindById(rootId);
            if (root is null)
            {
                return Results.NotFound(new { error = "root_not_found" });
            }

            var guard = PathGuard.ResolveSafe(root, path);
            if (!guard.IsOk)
            {
                return ForbiddenResult(MapPathError(guard.Error!.Value));
            }

            var resolved = guard.ResolvedPath!;
            if (Directory.Exists(resolved))
            {
                return Results.BadRequest(new { error = "path_is_directory" });
            }
            if (!File.Exists(resolved))
            {
                return Results.NotFound(new { error = "path_not_found" });
            }

            var fileName = Path.GetFileName(resolved);
            return Results.File(resolved, "application/octet-stream", fileName);
        });

        // ---------- Delete ----------------------------------------------------
        app.MapDelete("/api/files", async (
            [FromBody] DeleteRequest? body,
            IFileRootService rootService,
            EventHub hub) =>
        {
            if (body is null)
            {
                return Results.BadRequest(new { error = "body_required" });
            }
            if (body.Confirm != true)
            {
                return Results.BadRequest(new { error = "confirm_required" });
            }
            if (string.IsNullOrEmpty(body.RootId))
            {
                return Results.BadRequest(new { error = "rootId_required" });
            }
            if (body.Path is null)
            {
                return Results.BadRequest(new { error = "path_required" });
            }

            var root = rootService.FindById(body.RootId);
            if (root is null)
            {
                return Results.NotFound(new { error = "root_not_found" });
            }
            if (root.ReadOnly)
            {
                return ForbiddenResult("read_only_root");
            }

            var guard = PathGuard.ResolveSafe(root, body.Path);
            if (!guard.IsOk)
            {
                return ForbiddenResult(MapPathError(guard.Error!.Value));
            }

            var resolved = guard.ResolvedPath!;

            // MVP: directory delete is intentionally unsupported. Recursive delete
            // is too dangerous to expose without a deeper safety review.
            if (Directory.Exists(resolved))
            {
                return Results.BadRequest(new { error = "directory_delete_unsupported" });
            }
            if (!File.Exists(resolved))
            {
                return Results.NotFound(new { error = "path_not_found" });
            }

            File.Delete(resolved);

            await BroadcastFileEventAsync(hub, EventTypes.FileDeleted, new { rootId = body.RootId, path = body.Path })
                .ConfigureAwait(false);

            return Results.Ok(new { rootId = body.RootId, path = body.Path });
        });

        // ---------- Rename ----------------------------------------------------
        app.MapPost("/api/files/rename", async (
            RenameRequest? body,
            IFileRootService rootService,
            EventHub hub) =>
        {
            if (body is null)
            {
                return Results.BadRequest(new { error = "body_required" });
            }
            if (body.Confirm != true)
            {
                return Results.BadRequest(new { error = "confirm_required" });
            }
            if (string.IsNullOrEmpty(body.RootId))
            {
                return Results.BadRequest(new { error = "rootId_required" });
            }
            if (string.IsNullOrEmpty(body.Path))
            {
                return Results.BadRequest(new { error = "path_required" });
            }
            if (string.IsNullOrEmpty(body.NewName))
            {
                return Results.BadRequest(new { error = "newName_required" });
            }
            if (ContainsInvalidNameChar(body.NewName))
            {
                return Results.BadRequest(new { error = "invalid_name" });
            }

            var root = rootService.FindById(body.RootId);
            if (root is null)
            {
                return Results.NotFound(new { error = "root_not_found" });
            }
            if (root.ReadOnly)
            {
                return ForbiddenResult("read_only_root");
            }

            var srcGuard = PathGuard.ResolveSafe(root, body.Path);
            if (!srcGuard.IsOk)
            {
                return ForbiddenResult(MapPathError(srcGuard.Error!.Value));
            }
            var resolvedSource = srcGuard.ResolvedPath!;

            if (Directory.Exists(resolvedSource))
            {
                return Results.BadRequest(new { error = "path_is_directory" });
            }
            if (!File.Exists(resolvedSource))
            {
                return Results.NotFound(new { error = "path_not_found" });
            }

            // Build the destination relative path by swapping just the filename.
            var relativeDir = Path.GetDirectoryName(body.Path) ?? string.Empty;
            var newRelative = string.IsNullOrEmpty(relativeDir)
                ? body.NewName
                : Path.Combine(relativeDir, body.NewName);

            var destGuard = PathGuard.ResolveSafe(root, newRelative);
            if (!destGuard.IsOk)
            {
                return ForbiddenResult(MapPathError(destGuard.Error!.Value));
            }
            var resolvedDest = destGuard.ResolvedPath!;

            var overwrite = body.Overwrite ?? false;

            if (Directory.Exists(resolvedDest))
            {
                return Results.Json(
                    new { error = "destination_is_directory" },
                    statusCode: StatusCodes.Status409Conflict);
            }
            if (File.Exists(resolvedDest)
                && !string.Equals(resolvedDest, resolvedSource, StringComparison.OrdinalIgnoreCase)
                && !overwrite)
            {
                return Results.Json(new { error = "file_exists" }, statusCode: StatusCodes.Status409Conflict);
            }

            File.Move(resolvedSource, resolvedDest, overwrite: overwrite);

            await BroadcastFileEventAsync(
                hub,
                EventTypes.FileRenamed,
                new { rootId = body.RootId, fromPath = body.Path, toPath = newRelative }).ConfigureAwait(false);

            return Results.Ok(new
            {
                rootId = body.RootId,
                fromPath = body.Path,
                toPath = newRelative
            });
        });

        // ---------- Move ------------------------------------------------------
        app.MapPost("/api/files/move", async (
            MoveRequest? body,
            IFileRootService rootService,
            EventHub hub) =>
        {
            if (body is null)
            {
                return Results.BadRequest(new { error = "body_required" });
            }
            if (body.Confirm != true)
            {
                return Results.BadRequest(new { error = "confirm_required" });
            }
            if (string.IsNullOrEmpty(body.RootId))
            {
                return Results.BadRequest(new { error = "rootId_required" });
            }
            if (string.IsNullOrEmpty(body.FromPath))
            {
                return Results.BadRequest(new { error = "fromPath_required" });
            }
            if (string.IsNullOrEmpty(body.ToPath))
            {
                return Results.BadRequest(new { error = "toPath_required" });
            }

            var root = rootService.FindById(body.RootId);
            if (root is null)
            {
                return Results.NotFound(new { error = "root_not_found" });
            }
            if (root.ReadOnly)
            {
                return ForbiddenResult("read_only_root");
            }

            var fromGuard = PathGuard.ResolveSafe(root, body.FromPath);
            if (!fromGuard.IsOk)
            {
                return ForbiddenResult(MapPathError(fromGuard.Error!.Value));
            }
            var toGuard = PathGuard.ResolveSafe(root, body.ToPath);
            if (!toGuard.IsOk)
            {
                return ForbiddenResult(MapPathError(toGuard.Error!.Value));
            }

            var resolvedFrom = fromGuard.ResolvedPath!;
            var resolvedTo = toGuard.ResolvedPath!;

            if (Directory.Exists(resolvedFrom))
            {
                return Results.BadRequest(new { error = "path_is_directory" });
            }
            if (!File.Exists(resolvedFrom))
            {
                return Results.NotFound(new { error = "path_not_found" });
            }

            // Cross-volume detection: File.Move within the same root could still
            // span drives on Windows if a junction was set up, but we already
            // reject reparse-point paths in PathGuard. Compare GetPathRoot just
            // in case a future config legitimately maps two drives under one root.
            var fromVolume = Path.GetPathRoot(resolvedFrom);
            var toVolume = Path.GetPathRoot(resolvedTo);
            if (!string.Equals(fromVolume, toVolume, StringComparison.OrdinalIgnoreCase))
            {
                return Results.BadRequest(new { error = "cross_volume_move_unsupported" });
            }

            var overwrite = body.Overwrite ?? false;

            if (Directory.Exists(resolvedTo))
            {
                return Results.Json(
                    new { error = "destination_is_directory" },
                    statusCode: StatusCodes.Status409Conflict);
            }
            if (File.Exists(resolvedTo)
                && !string.Equals(resolvedFrom, resolvedTo, StringComparison.OrdinalIgnoreCase)
                && !overwrite)
            {
                return Results.Json(new { error = "file_exists" }, statusCode: StatusCodes.Status409Conflict);
            }

            var parent = Path.GetDirectoryName(resolvedTo);
            if (parent is not null && !Directory.Exists(parent))
            {
                return Results.NotFound(new { error = "parent_not_found" });
            }

            File.Move(resolvedFrom, resolvedTo, overwrite: overwrite);

            await BroadcastFileEventAsync(
                hub,
                EventTypes.FileMoved,
                new { rootId = body.RootId, fromPath = body.FromPath, toPath = body.ToPath }).ConfigureAwait(false);

            return Results.Ok(new
            {
                rootId = body.RootId,
                fromPath = body.FromPath,
                toPath = body.ToPath
            });
        });

        return app;
    }

    private static bool ContainsInvalidNameChar(string name)
    {
        if (string.IsNullOrEmpty(name))
        {
            return true;
        }

        // Reject "." and ".." outright — they are valid filename chars but
        // would let a caller traverse a directory level via "newName".
        if (name == "." || name == "..")
        {
            return true;
        }

        foreach (var ch in name)
        {
            if (ch < 0x20)
            {
                return true;
            }
            for (var i = 0; i < InvalidNameChars.Length; i++)
            {
                if (ch == InvalidNameChars[i])
                {
                    return true;
                }
            }
        }
        return false;
    }

    private static IResult ForbiddenResult(string error)
        => Results.Json(new { error }, statusCode: StatusCodes.Status403Forbidden);

    private static IResult PayloadTooLargeResult()
        => Results.Json(
            new { error = "file_too_large", maxBytes = MaxUploadSize },
            statusCode: StatusCodes.Status413PayloadTooLarge);

    private static string MapPathError(PathSafetyError err) => err switch
    {
        PathSafetyError.OutsideRoot => "path_outside_root",
        PathSafetyError.Absolute => "path_absolute",
        PathSafetyError.InvalidChar => "path_invalid_char",
        PathSafetyError.SymlinkEscape => "symlink_escape",
        PathSafetyError.ReparsePointInPath => "reparse_point_in_path",
        _ => "path_invalid",
    };

    private static async Task BroadcastFileEventAsync(EventHub hub, string eventType, object payload)
    {
        var json = JsonSerializer.SerializeToElement(
            payload,
            new JsonSerializerOptions(JsonSerializerDefaults.Web));
        var envelope = new EventEnvelope(eventType, json, DateTimeOffset.UtcNow);
        await hub.BroadcastAsync(envelope).ConfigureAwait(false);
    }
}

/// <summary>Body of <c>DELETE /api/files</c>. Confirm must be <c>true</c>.</summary>
public sealed record DeleteRequest(string? RootId, string? Path, bool? Confirm);

/// <summary>Body of <c>POST /api/files/rename</c>. Confirm must be <c>true</c>.</summary>
public sealed record RenameRequest(string? RootId, string? Path, string? NewName, bool? Confirm, bool? Overwrite);

/// <summary>Body of <c>POST /api/files/move</c>. Confirm must be <c>true</c>.</summary>
public sealed record MoveRequest(string? RootId, string? FromPath, string? ToPath, bool? Confirm, bool? Overwrite);
