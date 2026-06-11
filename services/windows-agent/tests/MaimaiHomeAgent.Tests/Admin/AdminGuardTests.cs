using MaimaiHomeAgent.Admin;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Options;

namespace MaimaiHomeAgent.Tests.Admin;

public sealed class AdminGuardTests
{
    [Fact]
    public void IsValidPassword_WithConfiguredPassword_ReturnsTrue()
    {
        var guard = CreateGuard("seganmsl");

        Assert.True(guard.IsValidPassword("seganmsl"));
    }

    [Fact]
    public void IsValidPassword_WithWrongPassword_ReturnsFalse()
    {
        var guard = CreateGuard("seganmsl");

        Assert.False(guard.IsValidPassword("wrong"));
    }

    [Fact]
    public void IsAuthorized_WithBearerPassword_ReturnsTrue()
    {
        var guard = CreateGuard("seganmsl");
        var ctx = new DefaultHttpContext();
        ctx.Request.Headers.Authorization = "Bearer seganmsl";

        Assert.True(guard.IsAuthorized(ctx));
    }

    [Fact]
    public void IsAuthorized_WithoutBearerPassword_ReturnsFalse()
    {
        var guard = CreateGuard("seganmsl");
        var ctx = new DefaultHttpContext();

        Assert.False(guard.IsAuthorized(ctx));
    }

    private static AdminGuard CreateGuard(string password) =>
        new(new StaticOptionsMonitor<AdminOptions>(new AdminOptions { Password = password }));

    private sealed class StaticOptionsMonitor<T> : IOptionsMonitor<T>
    {
        public StaticOptionsMonitor(T value)
        {
            CurrentValue = value;
        }

        public T CurrentValue { get; }

        public T Get(string? name) => CurrentValue;

        public IDisposable? OnChange(Action<T, string?> listener) => null;
    }
}
