using MaimaiHomeAgent.Launcher;

namespace MaimaiHomeAgent.Tests.Launcher;

public sealed class LauncherConfigValidatorTests
{
    [Fact]
    public void Validate_WithValidConfig_ReturnsNoErrors()
    {
        var options = new LauncherOptions
        {
            Items = new()
            {
                new LauncherItemOptions { Id = "mai", Name = "maimai", CommandLine = "echo mai", Key = "A", Enabled = true }
            }
        };

        var errors = LauncherConfigValidator.Validate(options);

        Assert.Empty(errors);
    }

    [Fact]
    public void Validate_WithDuplicateEnabledKeys_ReturnsDuplicateError()
    {
        var options = new LauncherOptions
        {
            Items = new()
            {
                new LauncherItemOptions { Id = "a", Name = "A", CommandLine = "echo a", Key = "A", Enabled = true },
                new LauncherItemOptions { Id = "b", Name = "B", CommandLine = "echo b", Key = "a", Enabled = true }
            }
        };

        var errors = LauncherConfigValidator.Validate(options);

        Assert.Contains(errors, error => error.Error == "launcher_item_key_duplicate");
    }

    [Fact]
    public void Validate_WithDuplicateDisabledKey_ReturnsNoDuplicateError()
    {
        var options = new LauncherOptions
        {
            Items = new()
            {
                new LauncherItemOptions { Id = "a", Name = "A", CommandLine = "echo a", Key = "A", Enabled = true },
                new LauncherItemOptions { Id = "b", Name = "B", CommandLine = "echo b", Key = "A", Enabled = false }
            }
        };

        var errors = LauncherConfigValidator.Validate(options);

        Assert.DoesNotContain(errors, error => error.Error == "launcher_item_key_duplicate");
    }

    [Fact]
    public void Validate_WithMissingRequiredFields_ReturnsErrors()
    {
        var options = new LauncherOptions
        {
            Items = new()
            {
                new LauncherItemOptions()
            }
        };

        var errors = LauncherConfigValidator.Validate(options);

        Assert.Contains(errors, error => error.Error == "launcher_item_id_required");
        Assert.Contains(errors, error => error.Error == "launcher_item_name_required");
        Assert.Contains(errors, error => error.Error == "launcher_item_command_required");
        Assert.Contains(errors, error => error.Error == "launcher_item_key_required");
    }
}
