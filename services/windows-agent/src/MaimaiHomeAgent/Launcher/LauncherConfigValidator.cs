namespace MaimaiHomeAgent.Launcher;

public static class LauncherConfigValidator
{
    public static IReadOnlyList<LauncherConfigError> Validate(LauncherOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);

        var errors = new List<LauncherConfigError>();
        if (options.CanvasWidth <= 0)
            errors.Add(new LauncherConfigError("launcher_canvas_width_invalid", "启动选择器宽度必须大于 0"));

        if (options.CanvasHeight <= 0)
            errors.Add(new LauncherConfigError("launcher_canvas_height_invalid", "启动选择器高度必须大于 0"));

        if (string.IsNullOrWhiteSpace(options.NavigateLeftKey))
            errors.Add(new LauncherConfigError("launcher_navigate_left_key_required", "启动选择器左移按键不能为空"));

        if (string.IsNullOrWhiteSpace(options.NavigateRightKey))
            errors.Add(new LauncherConfigError("launcher_navigate_right_key_required", "启动选择器右移按键不能为空"));

        if (string.IsNullOrWhiteSpace(options.ConfirmKey))
            errors.Add(new LauncherConfigError("launcher_confirm_key_required", "启动选择器确认按键不能为空"));

        if (string.IsNullOrWhiteSpace(options.StopKey))
            errors.Add(new LauncherConfigError("launcher_stop_key_required", "启动选择器关闭快捷键不能为空"));

        if (!string.IsNullOrWhiteSpace(options.NavigateLeftKey) &&
            !string.IsNullOrWhiteSpace(options.NavigateRightKey) &&
            string.Equals(options.NavigateLeftKey.Trim(), options.NavigateRightKey.Trim(),
                StringComparison.OrdinalIgnoreCase))
            errors.Add(new LauncherConfigError("launcher_navigation_keys_conflict", "启动选择器左右移动按键不能相同"));

        if (!string.IsNullOrWhiteSpace(options.ConfirmKey) &&
            ((!string.IsNullOrWhiteSpace(options.NavigateLeftKey) && string.Equals(options.ConfirmKey.Trim(),
                 options.NavigateLeftKey.Trim(), StringComparison.OrdinalIgnoreCase)) ||
              (!string.IsNullOrWhiteSpace(options.NavigateRightKey) && string.Equals(options.ConfirmKey.Trim(),
                  options.NavigateRightKey.Trim(), StringComparison.OrdinalIgnoreCase))))
            errors.Add(new LauncherConfigError("launcher_confirm_key_conflict", "启动选择器确认按键不能与移动按键相同"));

        if (!string.IsNullOrWhiteSpace(options.StopKey) &&
            ((!string.IsNullOrWhiteSpace(options.NavigateLeftKey) && string.Equals(options.StopKey.Trim(),
                 options.NavigateLeftKey.Trim(), StringComparison.OrdinalIgnoreCase)) ||
             (!string.IsNullOrWhiteSpace(options.NavigateRightKey) && string.Equals(options.StopKey.Trim(),
                 options.NavigateRightKey.Trim(), StringComparison.OrdinalIgnoreCase)) ||
             (!string.IsNullOrWhiteSpace(options.ConfirmKey) && string.Equals(options.StopKey.Trim(),
                 options.ConfirmKey.Trim(), StringComparison.OrdinalIgnoreCase))))
            errors.Add(new LauncherConfigError("launcher_stop_key_conflict", "启动选择器关闭快捷键不能与移动或确认按键相同"));

        foreach (var item in options.Items)
        {
            var label = string.IsNullOrWhiteSpace(item.Id) ? item.Name : item.Id;
            if (string.IsNullOrWhiteSpace(item.Id))
                errors.Add(new LauncherConfigError("launcher_item_id_required", "启动项 ID 不能为空"));

            if (string.IsNullOrWhiteSpace(item.Name))
                errors.Add(new LauncherConfigError("launcher_item_name_required", $"启动项 {label} 名称不能为空"));

            if (string.IsNullOrWhiteSpace(item.CommandLine))
                errors.Add(new LauncherConfigError("launcher_item_command_required", $"启动项 {label} 命令行不能为空"));

            if (string.IsNullOrWhiteSpace(item.StopCommandLine))
                errors.Add(new LauncherConfigError("launcher_item_stop_command_required", $"启动项 {label} 关闭命令行不能为空"));

            if (!string.IsNullOrWhiteSpace(item.WorkingDirectory))
            {
                var expanded = Environment.ExpandEnvironmentVariables(item.WorkingDirectory);
                if (!Directory.Exists(expanded))
                    errors.Add(new LauncherConfigError("launcher_item_working_directory_missing",
                        $"启动项 {label} 工作目录不存在"));
            }

            if (!string.IsNullOrWhiteSpace(item.StopWorkingDirectory))
            {
                var expanded = Environment.ExpandEnvironmentVariables(item.StopWorkingDirectory);
                if (!Directory.Exists(expanded))
                    errors.Add(new LauncherConfigError("launcher_item_stop_working_directory_missing",
                        $"启动项 {label} 关闭工作目录不存在"));
            }

            if (!string.IsNullOrWhiteSpace(item.IconPath))
            {
                var expanded = Environment.ExpandEnvironmentVariables(item.IconPath);
                if (!File.Exists(expanded))
                    errors.Add(new LauncherConfigError("launcher_item_icon_missing", $"启动项 {label} 图标文件不存在"));
            }
        }

        return errors;
    }
}

public sealed record LauncherConfigError(string Error, string Message);
