namespace MaimaiHomeAgent.Files;

/// <summary>
/// A configured file root accessible through the file management API.
/// </summary>
/// <param name="Id">Stable identifier used by API clients to reference this root.</param>
/// <param name="Name">Human-readable display label.</param>
/// <param name="Path">
/// Absolute filesystem path. May contain environment variables such as
/// <c>%USERPROFILE%</c> or <c>%LOCALAPPDATA%</c>; expansion happens at use time.
/// </param>
/// <param name="ReadOnly">When true, mutating operations on this root must be rejected.</param>
public sealed record FileRoot(string Id, string Name, string Path, bool ReadOnly);
