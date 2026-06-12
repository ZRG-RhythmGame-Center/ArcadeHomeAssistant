using MaimaiHomeAgent.Launcher;

namespace MaimaiHomeAgent.Tests.Launcher;

public sealed class LauncherConfigValidatorTests
{
    [Fact]
    public void Validate_WithValidConfig_ReturnsNoErrors()
    {
        var options = new LauncherOptions
        {
            NavigateLeftKey = "Left",
            NavigateRightKey = "Right",
            ConfirmKey = "Enter",
            Items = new()
            {
                new LauncherItemOptions { Id = "mai", Name = "maimai", Title = "maimai", CommandLine = "echo mai", StopCommandLine = "echo stop", Key = "A", Enabled = true }
            }
        };

        var errors = LauncherConfigValidator.Validate(options);

        Assert.Empty(errors);
    }

    [Fact]
    public void Validate_WithDuplicateEnabledKeys_ReturnsNoErrors()
    {
        var options = new LauncherOptions
        {
            NavigateLeftKey = "Left",
            NavigateRightKey = "Right",
            ConfirmKey = "Enter",
            Items = new()
            {
                new LauncherItemOptions { Id = "a", Name = "A", Title = "A", CommandLine = "echo a", StopCommandLine = "echo stop a", Key = "A", Enabled = true },
                new LauncherItemOptions { Id = "b", Name = "B", Title = "B", CommandLine = "echo b", StopCommandLine = "echo stop b", Key = "a", Enabled = true }
            }
        };

        var errors = LauncherConfigValidator.Validate(options);

        Assert.Empty(errors);
    }

    [Fact]
    public void Validate_WithDuplicateDisabledKey_ReturnsNoDuplicateError()
    {
        var options = new LauncherOptions
        {
            NavigateLeftKey = "Left",
            NavigateRightKey = "Right",
            ConfirmKey = "Enter",
            Items = new()
            {
                new LauncherItemOptions { Id = "a", Name = "A", Title = "A", CommandLine = "echo a", StopCommandLine = "echo stop a", Key = "A", Enabled = true },
                new LauncherItemOptions { Id = "b", Name = "B", Title = "B", CommandLine = "echo b", StopCommandLine = "echo stop b", Key = "A", Enabled = false }
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
        Assert.Contains(errors, error => error.Error == "launcher_item_stop_command_required");
    }

    [Fact]
    public void Validate_WithMissingNavigationKeys_ReturnsErrors()
    {
        var options = new LauncherOptions
        {
            NavigateLeftKey = "",
            NavigateRightKey = "",
            ConfirmKey = "",
            Items = new()
        };

        var errors = LauncherConfigValidator.Validate(options);

        Assert.Contains(errors, error => error.Error == "launcher_navigate_left_key_required");
        Assert.Contains(errors, error => error.Error == "launcher_navigate_right_key_required");
        Assert.Contains(errors, error => error.Error == "launcher_confirm_key_required");
    }
}
