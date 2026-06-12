namespace MaimaiHomeAgent.Files;

/// <summary>
///     Reasons a path can be rejected by <see cref="PathGuard" />.
///     Callers map these to HTTP 403 (or 400 for malformed input) — never 500.
/// </summary>
public enum PathSafetyError
{
    /// <summary>Path contains NUL, control characters, or Windows reserved chars (&lt;&gt;:"|?*).</summary>
    InvalidChar,

    /// <summary>Path is rooted/absolute (e.g. <c>C:\Windows</c> or <c>/etc/passwd</c>).</summary>
    Absolute,

    /// <summary>Resolved path falls outside the configured root.</summary>
    OutsideRoot,

    /// <summary>The target itself is a symlink whose final target leaves the root.</summary>
    SymlinkEscape,

    /// <summary>An intermediate directory between the root and the target is a reparse point (symlink/junction).</summary>
    ReparsePointInPath
}