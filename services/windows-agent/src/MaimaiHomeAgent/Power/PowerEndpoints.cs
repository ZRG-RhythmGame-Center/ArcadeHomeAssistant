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
            CancellationToken ct) =>
        {
            if (body is null || body.Confirm != true) return Results.BadRequest(new { error = "confirm_required" });

            if (!shutdown.IsAvailable) return UnavailableResult(shutdown.GetStatus());

            var result = await shutdown
                .ExecuteAsync(DescribeRequester(ctx), ct)
                .ConfigureAwait(false);

            if (result.Accepted) return Results.Ok(result.Status);

            if (result.Conflict)
                return Results.Json(
                    new
                    {
                        error = result.Error,
                        message = "远程关机正在执行",
                        status = result.Status
                    },
                    statusCode: StatusCodes.Status409Conflict);

            if (result.Error == "shutdown_failed")
                return Results.Json(
                    new
                    {
                        error = result.Error,
                        message = "远程关机执行失败",
                        status = result.Status
                    },
                    statusCode: StatusCodes.Status502BadGateway);

            return UnavailableResult(result.Status);
        });

        return app;
    }

    private static string DescribeRequester(HttpContext ctx)
    {
        var remoteIp = ctx.Connection.RemoteIpAddress?.ToString();
        return string.IsNullOrWhiteSpace(remoteIp) ? "unknown" : remoteIp;
    }

    private static IResult UnavailableResult(RemoteShutdownStatusDto status)
    {
        return Results.Json(
            new
            {
                error = "remote_shutdown_unavailable",
                message = "远程关机当前不可用",
                status
            },
            statusCode: StatusCodes.Status503ServiceUnavailable);
    }
}