namespace MaimaiHomeAgent.Files;

/// <summary>
///     Outcome of <see cref="PathGuard.ResolveSafe" />. Either an Ok with the
///     fully-normalized absolute path, or a Fail with a typed error.
/// </summary>
public sealed record PathGuardResult
{
    private PathGuardResult(bool isOk, string? resolvedPath, PathSafetyError? error)
    {
        IsOk = isOk;
        ResolvedPath = resolvedPath;
        Error = error;
    }

    public bool IsOk { get; }

    public string? ResolvedPath { get; }

    public PathSafetyError? Error { get; }

    public static PathGuardResult Ok(string resolvedPath)
    {
        ArgumentException.ThrowIfNullOrEmpty(resolvedPath);
        return new PathGuardResult(true, resolvedPath, null);
    }

    public static PathGuardResult Fail(PathSafetyError error)
    {
        return new PathGuardResult(false, null, error);
    }
}