namespace MaimaiHomeAgent.Launcher;

public static class LauncherEndpoints
{
    public static IEndpointRouteBuilder MapLauncherEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        app.MapGet("/api/launcher/status", (ILauncherService launcher) =>
            Results.Ok(launcher.GetStatus()));

        app.MapPost("/api/launcher/show",
            async (ILauncherService launcher, CancellationToken ct) =>
                ToResult(await launcher.ShowAsync(ct).ConfigureAwait(false)));

        app.MapPost("/api/launcher/start", async (
            StartLauncherItemRequest? request,
            ILauncherService launcher,
            CancellationToken ct) =>
        {
            if (request is null)
                return Results.BadRequest(new { error = "launcher_start_request_required", message = "启动请求不能为空" });

            return ToResult(await launcher.StartItemAsync(request.ItemId ?? string.Empty, ct).ConfigureAwait(false));
        });

        app.MapPost("/api/launcher/stop",
            async (ILauncherService launcher, CancellationToken ct) =>
                ToResult(await launcher.StopActiveItemAsync(ct).ConfigureAwait(false)));

        return app;
    }

    private static IResult ToResult(LauncherActionResult result)
    {
        if (result.Accepted) return Results.Ok(result.Status);

        var statusCode = result.Error switch
        {
            "launcher_item_already_active" => StatusCodes.Status409Conflict,
            "launcher_item_not_active" => StatusCodes.Status409Conflict,
            "launcher_item_not_found" => StatusCodes.Status404NotFound,
            "launcher_item_id_required" => StatusCodes.Status400BadRequest,
            _ => StatusCodes.Status502BadGateway
        };

        return Results.Json(new
        {
            error = result.Error,
            message = result.Message,
            status = result.Status
        }, statusCode: statusCode);
    }
}
