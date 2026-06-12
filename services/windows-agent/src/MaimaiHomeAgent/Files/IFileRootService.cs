namespace MaimaiHomeAgent.Files;

/// <summary>
///     Read-only registry of configured <see cref="FileRoot" /> entries, with
///     support for hot-reload from configuration.
/// </summary>
public interface IFileRootService
{
    /// <summary>Snapshot of currently configured roots.</summary>
    IReadOnlyList<FileRoot> ListRoots();

    /// <summary>Find a root by its stable id, or null if unknown.</summary>
    FileRoot? FindById(string id);

    /// <summary>Replace the current root set atomically.</summary>
    void Reload(IEnumerable<FileRoot> roots);
}