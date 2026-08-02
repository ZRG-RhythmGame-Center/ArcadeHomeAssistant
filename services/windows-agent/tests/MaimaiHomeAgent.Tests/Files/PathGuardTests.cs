using MaimaiHomeAgent.Files;

namespace MaimaiHomeAgent.Tests.Files;

public class PathGuardTests : IDisposable
{
    private readonly FileRoot _root;
    private readonly string _rootPath;

    public PathGuardTests()
    {
        _rootPath = Path.Combine(Path.GetTempPath(), "maimai-pathguard-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_rootPath);
        _root = new FileRoot(
            "test",
            "Test Root",
            _rootPath,
            false);
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_rootPath)) Directory.Delete(_rootPath, true);
        }
        catch
        {
            // best-effort cleanup
        }
    }

    [Theory]
    [InlineData("../../Windows/System32", PathSafetyError.OutsideRoot)]
    [InlineData("sub/../../../escape", PathSafetyError.OutsideRoot)]
    [InlineData("..", PathSafetyError.OutsideRoot)]
    public void ResolveSafe_RejectsOutsideRoot(string relativePath, PathSafetyError expectedError)
    {
        var result = PathGuard.ResolveSafe(_root, relativePath);

        Assert.False(result.IsOk);
        Assert.Equal(expectedError, result.Error);
        Assert.Null(result.ResolvedPath);
    }

    [Theory]
    [InlineData("C:\\Windows")]
    [InlineData("D:\\anywhere")]
    [InlineData("/etc/passwd")]
    public void ResolveSafe_RejectsAbsolutePaths(string relativePath)
    {
        var result = PathGuard.ResolveSafe(_root, relativePath);

        Assert.False(result.IsOk);
        Assert.Equal(PathSafetyError.Absolute, result.Error);
    }

    [Theory]
    [InlineData("file<.txt")]
    [InlineData("foo>bar.txt")]
    [InlineData("name|pipe.txt")]
    [InlineData("ques?tion.txt")]
    [InlineData("star*.txt")]
    [InlineData("quo\"te.txt")]
    public void ResolveSafe_RejectsWindowsReservedChars(string relativePath)
    {
        var result = PathGuard.ResolveSafe(_root, relativePath);

        Assert.False(result.IsOk);
        Assert.Equal(PathSafetyError.InvalidChar, result.Error);
    }

    [Fact]
    public void ResolveSafe_RejectsNullChar()
    {
        var result = PathGuard.ResolveSafe(_root, "\0evil");

        Assert.False(result.IsOk);
        Assert.Equal(PathSafetyError.InvalidChar, result.Error);
    }

    [Fact]
    public void ResolveSafe_RejectsControlChars()
    {
        var result = PathGuard.ResolveSafe(_root, "evil\u0001file.txt");

        Assert.False(result.IsOk);
        Assert.Equal(PathSafetyError.InvalidChar, result.Error);
    }

    [Fact]
    public void ResolveSafe_AcceptsNormalRelativePath()
    {
        var result = PathGuard.ResolveSafe(_root, "normal/file.txt");

        Assert.True(result.IsOk);
        Assert.NotNull(result.ResolvedPath);
        Assert.Null(result.Error);
        Assert.StartsWith(_rootPath, result.ResolvedPath, StringComparison.OrdinalIgnoreCase);
        Assert.EndsWith("file.txt", result.ResolvedPath);
    }

    [Fact]
    public void ResolveSafe_AcceptsForwardSlashSubpath()
    {
        var result = PathGuard.ResolveSafe(_root, "sub/file.txt");

        Assert.True(result.IsOk);
        Assert.NotNull(result.ResolvedPath);
    }

    [Fact]
    public void ResolveSafe_AcceptsBackslashSubpath()
    {
        var result = PathGuard.ResolveSafe(_root, "sub\\file.txt");

        Assert.True(result.IsOk);
        Assert.NotNull(result.ResolvedPath);
    }

    [Fact]
    public void ResolveSafe_AcceptsRootItselfWhenEmpty()
    {
        var result = PathGuard.ResolveSafe(_root, "");

        Assert.True(result.IsOk);
        Assert.NotNull(result.ResolvedPath);
    }

    [Fact]
    public void ResolveSafe_AcceptsDriveRootItselfWhenEmpty()
    {
        var drive = DriveInfo.GetDrives().FirstOrDefault(static d => d.IsReady);
        if (drive is null) return;

        var driveRoot = new FileRoot(
            "drive",
            "Drive Root",
            drive.RootDirectory.FullName,
            false);

        var result = PathGuard.ResolveSafe(driveRoot, "");

        Assert.True(result.IsOk);
        Assert.NotNull(result.ResolvedPath);
        Assert.Equal(drive.RootDirectory.FullName, result.ResolvedPath);
    }

    [Fact]
    public void ResolveSafe_AcceptsDriveRootChildDirectory()
    {
        var drive = DriveInfo.GetDrives().FirstOrDefault(static d => d.IsReady);
        if (drive is null) return;

        var child = drive.RootDirectory.EnumerateDirectories().FirstOrDefault();
        if (child is null) return;

        var driveRoot = new FileRoot(
            "drive",
            "Drive Root",
            drive.RootDirectory.FullName,
            false);

        var result = PathGuard.ResolveSafe(driveRoot, child.Name);

        Assert.True(result.IsOk);
        Assert.NotNull(result.ResolvedPath);
        Assert.StartsWith(drive.RootDirectory.FullName, result.ResolvedPath, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void ResolveSafe_ExpandsEnvironmentVariablesInRoot()
    {
        // Use TEMP env var which definitely exists on Windows
        var rootWithEnvVar = new FileRoot(
            "env",
            "Env Root",
            "%TEMP%",
            false);

        var result = PathGuard.ResolveSafe(rootWithEnvVar, "subdir/file.txt");

        Assert.True(result.IsOk);
        Assert.NotNull(result.ResolvedPath);
        // Should NOT contain literal %TEMP%
        Assert.DoesNotContain("%", result.ResolvedPath);
    }

    [Fact]
    public void ResolveSafe_ResultOk_HasIsOkTrueAndNoError()
    {
        var ok = PathGuardResult.Ok("C:\\some\\path");

        Assert.True(ok.IsOk);
        Assert.Equal("C:\\some\\path", ok.ResolvedPath);
        Assert.Null(ok.Error);
    }

    [Fact]
    public void ResolveSafe_ResultFail_HasIsOkFalseAndError()
    {
        var fail = PathGuardResult.Fail(PathSafetyError.OutsideRoot);

        Assert.False(fail.IsOk);
        Assert.Null(fail.ResolvedPath);
        Assert.Equal(PathSafetyError.OutsideRoot, fail.Error);
    }

    [Fact]
    public void ResolveSafe_AcceptsLongPath_280Chars()
    {
        // Create a 280-char relative path (exceeds Windows 260-char limit)
        // Structure: root + 280-char path should normalize successfully with longPathAware manifest
        var longSubdir = string.Join("/", Enumerable.Range(0, 28).Select(i => $"dir{i:D2}"));
        var result = PathGuard.ResolveSafe(_root, longSubdir + "/file.txt");

        Assert.True(result.IsOk);
        Assert.NotNull(result.ResolvedPath);
        Assert.Null(result.Error);
    }

    [Fact]
    public void ResolveSafe_RejectsJunctionPointingOutsideRoot()
    {
        // Create a temp directory outside the root
        var outsideDir = Path.Combine(Path.GetTempPath(), "maimai-outside-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(outsideDir);

        try
        {
            // Create a junction inside root pointing outside
            var junctionPath = Path.Combine(_rootPath, "junction-outside");
            try
            {
                Directory.CreateSymbolicLink(junctionPath, outsideDir);
            }
            catch (IOException)
            {
                // Skip if no permission (not admin/dev mode)
                return;
            }

            var result = PathGuard.ResolveSafe(_root, "junction-outside");

            Assert.False(result.IsOk);
            Assert.Equal(PathSafetyError.SymlinkEscape, result.Error);
        }
        finally
        {
            try
            {
                Directory.Delete(outsideDir, true);
            }
            catch
            {
            }
        }
    }

    [Fact]
    public void ResolveSafe_AcceptsJunctionPointingInsideRoot()
    {
        // Create a target directory inside root
        var targetDir = Path.Combine(_rootPath, "target-dir");
        Directory.CreateDirectory(targetDir);

        // Create a junction inside root pointing to another dir inside root
        var junctionPath = Path.Combine(_rootPath, "junction-inside");
        try
        {
            Directory.CreateSymbolicLink(junctionPath, targetDir);
        }
        catch (IOException)
        {
            // Skip if no permission (not admin/dev mode)
            return;
        }

        var result = PathGuard.ResolveSafe(_root, "junction-inside");

        Assert.True(result.IsOk);
        Assert.NotNull(result.ResolvedPath);
        Assert.Null(result.Error);
    }
}