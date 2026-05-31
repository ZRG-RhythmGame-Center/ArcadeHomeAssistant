using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Files;

/// <summary>
/// In-memory implementation of <see cref="IFileRootService"/> that reads its
/// initial state from configuration section <c>FileRoots</c> and supports
/// thread-safe hot-reload via <see cref="Reload"/>.
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

        var initial = new List<FileRoot>();
        configuration.GetSection("FileRoots").Bind(initial);
        _roots = NormalizeRoots(initial);

        _logger.LogInformation(
            "FileRootService initialized with {Count} root(s)",
            _roots.Count);
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
        if (string.IsNullOrEmpty(id))
        {
            return null;
        }

        _lock.EnterReadLock();
        try
        {
            for (var i = 0; i < _roots.Count; i++)
            {
                if (string.Equals(_roots[i].Id, id, StringComparison.Ordinal))
                {
                    return _roots[i];
                }
            }
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

    public void Dispose()
    {
        _lock.Dispose();
    }

    private static List<FileRoot> NormalizeRoots(IEnumerable<FileRoot> source)
    {
        var result = new List<FileRoot>();
        foreach (var raw in source)
        {
            if (raw is null)
            {
                continue;
            }

            // Pre-expand env vars so consumers see real paths in ListRoots(),
            // but PathGuard still re-expands defensively at validation time.
            var expandedPath = Environment.ExpandEnvironmentVariables(raw.Path ?? string.Empty);

            result.Add(new FileRoot(
                Id: raw.Id ?? string.Empty,
                Name: raw.Name ?? raw.Id ?? string.Empty,
                Path: expandedPath,
                ReadOnly: raw.ReadOnly));
        }
        return result;
    }
}
