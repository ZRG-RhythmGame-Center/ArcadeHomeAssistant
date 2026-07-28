namespace MaimaiHomeAgent.Settings;

public static class SettingsEndpoints
{
    public static IEndpointRouteBuilder MapSettingsEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        app.MapGet("/api/settings",
            async (IAgentSettingsService settings, CancellationToken ct) =>
                Results.Ok(await settings.GetAsync(ct).ConfigureAwait(false)));

        app.MapPut("/api/settings", async (
            AgentSettingsUpdateRequest? request,
            IAgentSettingsService settings,
            CancellationToken ct) =>
        {
            if (request is null)
                return Results.BadRequest(new { error = "settings_request_required", message = "设置请求不能为空" });

            var result = await settings.UpdateAsync(request, ct).ConfigureAwait(false);
            if (result.Success) return Results.Ok(result.Settings);

            var statusCode =
                result.Errors.Any(error => error.Error.EndsWith("duplicate", StringComparison.OrdinalIgnoreCase))
                    ? StatusCodes.Status409Conflict
                    : StatusCodes.Status400BadRequest;
            return Results.Json(new { error = "settings_validation_failed", errors = result.Errors },
                statusCode: statusCode);
        });

        return app;
    }
}
