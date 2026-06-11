using MaimaiHomeAgent.Admin;

namespace MaimaiHomeAgent.Settings;

public static class SettingsEndpoints
{
    public static IEndpointRouteBuilder MapSettingsEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        app.MapGet("/api/settings", async (HttpContext ctx, AdminGuard guard, IAgentSettingsService settings, CancellationToken ct) =>
        {
            if (!guard.IsAuthorized(ctx))
            {
                return AdminEndpoints.UnauthorizedResult();
            }

            return Results.Ok(await settings.GetAsync(ct).ConfigureAwait(false));
        });

        app.MapPut("/api/settings", async (
            HttpContext ctx,
            AgentSettingsUpdateRequest? request,
            AdminGuard guard,
            IAgentSettingsService settings,
            CancellationToken ct) =>
        {
            if (!guard.IsAuthorized(ctx))
            {
                return AdminEndpoints.UnauthorizedResult();
            }

            if (request is null)
            {
                return Results.BadRequest(new { error = "settings_request_required", message = "设置请求不能为空" });
            }

            var result = await settings.UpdateAsync(request, ct).ConfigureAwait(false);
            if (result.Success)
            {
                return Results.Ok(result.Settings);
            }

            var statusCode = result.Errors.Any(error => error.Error.EndsWith("duplicate", StringComparison.OrdinalIgnoreCase))
                ? StatusCodes.Status409Conflict
                : StatusCodes.Status400BadRequest;
            return Results.Json(new { error = "settings_validation_failed", errors = result.Errors }, statusCode: statusCode);
        });

        return app;
    }
}
