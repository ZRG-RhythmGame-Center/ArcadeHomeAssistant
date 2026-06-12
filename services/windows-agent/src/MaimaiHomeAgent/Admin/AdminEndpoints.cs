namespace MaimaiHomeAgent.Admin;

public static class AdminEndpoints
{
    public static IEndpointRouteBuilder MapAdminEndpoints(this IEndpointRouteBuilder app)
    {
        ArgumentNullException.ThrowIfNull(app);

        app.MapPost("/api/admin/session", (AdminLoginRequest? request, AdminGuard guard) =>
        {
            if (guard.IsValidPassword(request?.Password)) return Results.Ok(new AdminSessionResponse(true));

            return UnauthorizedResult();
        });

        return app;
    }

    public static IResult UnauthorizedResult()
    {
        return Results.Json(
            new
            {
                error = "admin_unauthorized",
                message = "管理员密码无效"
            },
            statusCode: StatusCodes.Status401Unauthorized);
    }
}

public sealed record AdminLoginRequest(string? Password);

public sealed record AdminSessionResponse(bool Authenticated);