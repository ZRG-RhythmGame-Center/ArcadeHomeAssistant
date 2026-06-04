using System.Security.Cryptography;
using System.Text;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Power;

public static class PowerEndpoints
{
    public static IEndpointRouteBuilder MapPowerEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        app.MapGet("/api/power/shutdown", (IRemoteShutdownService shutdown) =>
            Results.Ok(shutdown.GetStatus()));

        app.MapPost("/api/power/shutdown", async (
            HttpContext ctx,
            ExecuteShutdownRequest? body,
            IRemoteShutdownService shutdown,
            IOptionsMonitor<RemoteShutdownOptions> options,
            CancellationToken ct) =>
        {
            if (body is null || body.Confirm != true)
            {
                return Results.BadRequest(new { error = "confirm_required" });
            }

            if (!shutdown.IsAvailable)
            {
                return UnavailableResult(shutdown.GetStatus());
            }

            if (!IsAuthorized(ctx, options.CurrentValue))
            {
                return UnauthorizedResult();
            }

            var result = await shutdown
                .ExecuteAsync(DescribeRequester(ctx), ct)
                .ConfigureAwait(false);

            if (result.Accepted)
            {
                return Results.Ok(result.Status);
            }

            if (result.Conflict)
            {
                return Results.Json(
                    new
                    {
                        error = result.Error,
                        message = "远程关机正在执行",
                        status = result.Status
                    },
                    statusCode: StatusCodes.Status409Conflict);
            }

            if (result.Error == "shutdown_failed")
            {
                return Results.Json(
                    new
                    {
                        error = result.Error,
                        message = "远程关机执行失败",
                        status = result.Status
                    },
                    statusCode: StatusCodes.Status502BadGateway);
            }

            return UnavailableResult(result.Status);
        });

        return app;
    }

    private static bool IsAuthorized(HttpContext ctx, RemoteShutdownOptions options)
    {
        var expected = options.ControlToken;
        if (string.IsNullOrWhiteSpace(expected))
        {
            return false;
        }

        var header = ctx.Request.Headers.Authorization.ToString();
        const string prefix = "Bearer ";
        if (!header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var provided = header[prefix.Length..].Trim();
        if (string.IsNullOrEmpty(provided))
        {
            return false;
        }

        var expectedBytes = Encoding.UTF8.GetBytes(expected);
        var providedBytes = Encoding.UTF8.GetBytes(provided);
        return expectedBytes.Length == providedBytes.Length &&
            CryptographicOperations.FixedTimeEquals(expectedBytes, providedBytes);
    }

    private static string DescribeRequester(HttpContext ctx)
    {
        var remoteIp = ctx.Connection.RemoteIpAddress?.ToString();
        return string.IsNullOrWhiteSpace(remoteIp) ? "unknown" : remoteIp;
    }

    private static IResult UnauthorizedResult() =>
        Results.Json(
            new
            {
                error = "unauthorized",
                message = "远程关机需要有效控制令牌"
            },
            statusCode: StatusCodes.Status401Unauthorized);

    private static IResult UnavailableResult(RemoteShutdownStatusDto status) =>
        Results.Json(
            new
            {
                error = "remote_shutdown_unavailable",
                message = "远程关机当前不可用",
                status
            },
            statusCode: StatusCodes.Status503ServiceUnavailable);
}
