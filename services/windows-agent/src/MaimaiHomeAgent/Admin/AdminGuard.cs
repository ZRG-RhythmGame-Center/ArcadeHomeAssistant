using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Admin;

public sealed class AdminGuard
{
    private readonly IOptionsMonitor<AdminOptions> _options;

    public AdminGuard(IOptionsMonitor<AdminOptions> options)
    {
        _options = options;
    }

    public bool IsAuthorized(HttpContext ctx)
    {
        ArgumentNullException.ThrowIfNull(ctx);
        return IsValidBearerToken(ctx.Request.Headers.Authorization.ToString());
    }

    public bool IsValidPassword(string? password) => IsValidSecret(password?.Trim());

    private bool IsValidBearerToken(string header)
    {
        const string prefix = "Bearer ";
        if (!header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        return IsValidSecret(header[prefix.Length..].Trim());
    }

    private bool IsValidSecret(string? provided)
    {
        var expected = _options.CurrentValue.Password;
        if (string.IsNullOrWhiteSpace(expected) || string.IsNullOrEmpty(provided))
        {
            return false;
        }

        var expectedBytes = Encoding.UTF8.GetBytes(expected);
        var providedBytes = Encoding.UTF8.GetBytes(provided);
        return expectedBytes.Length == providedBytes.Length &&
            CryptographicOperations.FixedTimeEquals(expectedBytes, providedBytes);
    }
}
