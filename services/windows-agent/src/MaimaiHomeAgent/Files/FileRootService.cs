namespace MaimaiHomeAgent.Files;

/// <summary>
///     In-memory implementation of <see cref="IFileRootService" /> that reads its
///     initial state from configuration section <c>FileRoots</c> and supports
///     thread-safe hot-reload via <see cref="Reload" />.
/// </summary>
public sealed class FileRootService : IFileRootService, IDisposable
{
    private readonly ReaderWriterLockSlim _lock = new(LockRecursionPolicy.NoRecursion);
    private readonly ILogger<FileRootService> _logger;
    private List<FileRoot> _roots;

    public FileRootService(IConfiguration configuration, ILogger<FileRootService> logger)
    {
        ArgumentNullException.ThrowIfNull(configuration);
        _logger = logger ?? throw new ArgumentNullException(nameof(logger));

        var initial = LoadConfiguredRoots(configuration.GetSection("FileRoots"));
        _roots = NormalizeRoots(initial);

        _logger.LogInformation(
            "FileRootService initialized with {Count} root(s)",
            _roots.Count);
    }

    public void Dispose()
    {
        _lock.Dispose();
    }

    public IReadOnlyList<FileRoot> ListRoots()
    {
        _lock.EnterReadLock();
        try
        {
            // Return a snapshot copy so callers can't mutate internal state.
            return _roots.ToList();
        }
        finally
        {
            _lock.ExitReadLock();
        }
    }

    public FileRoot? FindById(string id)
    {
        if (string.IsNullOrEmpty(id)) return null;

        _lock.EnterReadLock();
        try
        {
            for (var i = 0; i < _roots.Count; i++)
                if (string.Equals(_roots[i].Id, id, StringComparison.Ordinal))
                    return _roots[i];

            return null;
        }
        finally
        {
            _lock.ExitReadLock();
        }
    }

    public void Reload(IEnumerable<FileRoot> roots)
    {
        ArgumentNullException.ThrowIfNull(roots);
        var normalized = NormalizeRoots(roots);

        _lock.EnterWriteLock();
        try
        {
            _roots = normalized;
        }
        finally
        {
            _lock.ExitWriteLock();
        }

        _logger.LogInformation(
            "FileRootService reloaded with {Count} root(s)",
            normalized.Count);
    }

    private static List<FileRoot> NormalizeRoots(IEnumerable<FileRoot> source)
    {
        var result = new List<FileRoot>();
        foreach (var raw in source)
        {
            if (raw is null) continue;

            // Pre-expand env vars so consumers see real paths in ListRoots(),
            // but PathGuard still re-expands defensively at validation time.
            var expandedPath = Environment.ExpandEnvironmentVariables(raw.Path ?? string.Empty);

            result.Add(new FileRoot(
                raw.Id ?? string.Empty,
                raw.Name ?? raw.Id ?? string.Empty,
                expandedPath,
                raw.ReadOnly));
        }

        return result;
    }

    private static List<FileRoot> LoadConfiguredRoots(IConfigurationSection section)
    {
        var result = new List<FileRoot>();
        foreach (var child in section.GetChildren())
            if (child.GetChildren().Any())
            {
                var root = child.Get<FileRoot>();
                if (root is not null) result.Add(root);
            }
            else if (!string.IsNullOrWhiteSpace(child.Value))
            {
                if (child.Value.Trim() == "*") return LoadMachineRoots();

                result.Add(CreateRootFromPath(child.Value, result.Count + 1));
            }

        return result.Count == 0 ? LoadMachineRoots() : result;
    }

    private static List<FileRoot> LoadMachineRoots()
    {
        return DriveInfo.GetDrives()
            .Where(static drive => drive.IsReady)
            .Select(static drive =>
            {
                var rootPath = drive.RootDirectory.FullName;
                var label = string.IsNullOrWhiteSpace(drive.VolumeLabel)
                    ? rootPath
                    : $"{drive.VolumeLabel} ({rootPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)})";
                var id = ToRootId(rootPath, 1);
                return new FileRoot(id, label, rootPath, false);
            })
            .ToList();
    }

    private static FileRoot CreateRootFromPath(string path, int fallbackIndex)
    {
        var expandedPath = Environment.ExpandEnvironmentVariables(path);
        var trimmed = expandedPath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
        var name = Path.GetFileName(trimmed);
        if (string.IsNullOrWhiteSpace(name)) name = expandedPath;

        var id = ToRootId(name, fallbackIndex);
        return new FileRoot(id, name, path, false);
    }

    private static string ToRootId(string name, int fallbackIndex)
    {
        var chars = name
            .Trim()
            .ToLowerInvariant()
            .Select(static c => char.IsAsciiLetterOrDigit(c) ? c : '-')
            .ToArray();
        var id = new string(chars).Trim('-');
        return string.IsNullOrWhiteSpace(id) ? $"root-{fallbackIndex}" : id;
    }
}