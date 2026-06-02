namespace MaimaiHomeAgent.Files;

/// <summary>
/// Validates and normalizes user-provided relative paths against a configured
/// <see cref="FileRoot"/>. All file operations MUST funnel through
/// <see cref="ResolveSafe"/> before touching disk.
/// </summary>
/// <remarks>
/// PathGuard does NOT throw on invalid input. It returns a typed
/// <see cref="PathGuardResult"/> so callers can map errors to HTTP 403/400
/// responses without exception-driven control flow.
/// </remarks>
public static class PathGuard
{
    // Windows reserved characters that are invalid in any filename component.
    // These are rejected even though Path.GetFullPath would tolerate some of them.
    private static readonly char[] WindowsReservedChars = ['<', '>', ':', '"', '|', '?', '*'];

    /// <summary>
    /// Resolve <paramref name="relativePath"/> against <paramref name="root"/>
    /// and ensure the result stays inside the root, with no symlink/junction
    /// escape hatches.
    /// </summary>
    public static PathGuardResult ResolveSafe(FileRoot root, string relativePath)
    {
        ArgumentNullException.ThrowIfNull(root);
        ArgumentNullException.ThrowIfNull(relativePath);

        // 1. Reject rooted/absolute paths FIRST. "C:\\Windows" contains ':'
        //    which is reserved, but it's a drive specifier rather than a content
        //    char, so absolute-path detection must take priority over the char filter.
        if (Path.IsPathRooted(relativePath))
        {
            return PathGuardResult.Fail(PathSafetyError.Absolute);
        }

        // 2. Reject NUL, control chars, Windows reserved chars in the (now
        //    confirmed-relative) path body.
        if (ContainsInvalidChar(relativePath))
        {
            return PathGuardResult.Fail(PathSafetyError.InvalidChar);
        }

        // 3. Expand env vars in root.Path then normalize the root itself.
        var expandedRoot = Path.GetFullPath(
            Environment.ExpandEnvironmentVariables(root.Path));

        // Trim a single trailing separator to keep prefix comparisons stable.
        expandedRoot = TrimTrailingSeparator(expandedRoot);

        // 4. Combine + GetFullPath collapses ".." segments.
        string fullPath;
        try
        {
            fullPath = Path.GetFullPath(Path.Combine(expandedRoot, relativePath));
        }
        catch (ArgumentException)
        {
            // GetFullPath throws on certain pathological inputs that slipped
            // past the char filter; treat as invalid char rather than crash.
            return PathGuardResult.Fail(PathSafetyError.InvalidChar);
        }

        fullPath = TrimTrailingSeparator(fullPath);

        // 5. Prefix check (case-insensitive on Windows).
        if (!IsWithinRoot(fullPath, expandedRoot))
        {
            return PathGuardResult.Fail(PathSafetyError.OutsideRoot);
        }

        // 6. If the target itself exists, follow any symlink to its final
        //    target and ensure that, too, stays inside the root.
        if (!fullPath.Equals(expandedRoot, StringComparison.OrdinalIgnoreCase) &&
            (File.Exists(fullPath) || Directory.Exists(fullPath)))
        {
            FileSystemInfo info = Directory.Exists(fullPath)
                ? new DirectoryInfo(fullPath)
                : new FileInfo(fullPath);

            try
            {
                var finalTarget = info.ResolveLinkTarget(returnFinalTarget: true);
                if (finalTarget is not null)
                {
                    var finalFull = Path.GetFullPath(finalTarget.FullName);
                    finalFull = TrimTrailingSeparator(finalFull);
                    if (!IsWithinRoot(finalFull, expandedRoot))
                    {
                        return PathGuardResult.Fail(PathSafetyError.SymlinkEscape);
                    }
                }
            }
            catch (IOException)
            {
                // ResolveLinkTarget can throw on broken/cyclic links; treat as escape.
                return PathGuardResult.Fail(PathSafetyError.SymlinkEscape);
            }
        }

        // 7. Walk every intermediate directory between expandedRoot and
        //    fullPath. If any of them is a reparse point (symlink/junction),
        //    the final resolved path can no longer be trusted.
        var reparseError = WalkForReparsePoint(expandedRoot, fullPath);
        if (reparseError is not null)
        {
            return PathGuardResult.Fail(reparseError.Value);
        }

        // 8. All checks passed.
        return PathGuardResult.Ok(fullPath);
    }

    private static bool ContainsInvalidChar(string value)
    {
        foreach (var ch in value)
        {
            // NUL and other ASCII control chars (< 0x20).
            if (ch < 0x20)
            {
                return true;
            }

            // Windows reserved chars. We deliberately allow ':' inside the
            // string only when it appears as a drive letter — but at this
            // point we've already established the path is NOT rooted, so
            // ':' anywhere is suspicious and rejected.
            for (var i = 0; i < WindowsReservedChars.Length; i++)
            {
                if (ch == WindowsReservedChars[i])
                {
                    return true;
                }
            }
        }

        return false;
    }

    private static bool IsWithinRoot(string fullPath, string expandedRoot)
    {
        if (fullPath.Equals(expandedRoot, StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        var prefix = EndsWithSeparator(expandedRoot)
            ? expandedRoot
            : expandedRoot + Path.DirectorySeparatorChar;
        return fullPath.StartsWith(prefix, StringComparison.OrdinalIgnoreCase);
    }

    private static bool EndsWithSeparator(string path)
    {
        if (path.Length == 0)
        {
            return false;
        }

        var last = path[^1];
        return last == Path.DirectorySeparatorChar || last == Path.AltDirectorySeparatorChar;
    }

    private static string TrimTrailingSeparator(string path)
    {
        if (path.Length <= 1)
        {
            return path;
        }

        var last = path[^1];
        if (last == Path.DirectorySeparatorChar || last == Path.AltDirectorySeparatorChar)
        {
            // Preserve "C:\" (drive root) — trimming would yield "C:" which
            // GetFullPath treats as the current dir on that drive.
            if (path.Length == 3 && path[1] == ':')
            {
                return path;
            }

            return path[..^1];
        }

        return path;
    }

    private static PathSafetyError? WalkForReparsePoint(string expandedRoot, string fullPath)
    {
        // If the target is exactly the root, nothing to walk.
        if (fullPath.Equals(expandedRoot, StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        // Compute the relative segment list between root and target.
        // Both are already absolute and normalized.
        var rootDir = new DirectoryInfo(expandedRoot);
        var current = new DirectoryInfo(Path.GetDirectoryName(fullPath) ?? expandedRoot);

        // Collect ancestors from `current` up to (but not including) the root.
        var ancestors = new List<DirectoryInfo>();
        while (current is not null &&
               !current.FullName.Equals(rootDir.FullName, StringComparison.OrdinalIgnoreCase))
        {
            ancestors.Add(current);
            current = current.Parent;

            // Defensive: if we walked past root without matching, the target
            // wasn't actually inside root. The earlier prefix check should
            // have prevented this; bail out without false positives.
            if (current is null)
            {
                return null;
            }
        }

        // For each intermediate directory that exists on disk, check the
        // ReparsePoint attribute. Non-existent ancestors (the path is being
        // created) cannot be reparse points by definition.
        foreach (var ancestor in ancestors)
        {
            if (!ancestor.Exists)
            {
                continue;
            }

            if ((ancestor.Attributes & FileAttributes.ReparsePoint) != 0)
            {
                return PathSafetyError.ReparsePointInPath;
            }
        }

        return null;
    }
}
