namespace MaimaiHomeAgent.Launcher;

public static class LauncherConfigValidator
{
    public static IReadOnlyList<LauncherConfigError> Validate(LauncherOptions options)
    {
        ArgumentNullException.ThrowIfNull(options);

        var errors = new List<LauncherConfigError>();
        if (options.CanvasWidth <= 0)
        {
            errors.Add(new LauncherConfigError("launcher_canvas_width_invalid", "启动选择器宽度必须大于 0"));
        }

        if (options.CanvasHeight <= 0)
        {
            errors.Add(new LauncherConfigError("launcher_canvas_height_invalid", "启动选择器高度必须大于 0"));
        }

        var enabledKeys = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var item in options.Items)
        {
            var label = string.IsNullOrWhiteSpace(item.Id) ? item.Name : item.Id;
            if (string.IsNullOrWhiteSpace(item.Id))
            {
                errors.Add(new LauncherConfigError("launcher_item_id_required", "启动项 ID 不能为空"));
            }

            if (string.IsNullOrWhiteSpace(item.Name))
            {
                errors.Add(new LauncherConfigError("launcher_item_name_required", $"启动项 {label} 名称不能为空"));
            }

            if (string.IsNullOrWhiteSpace(item.CommandLine))
            {
                errors.Add(new LauncherConfigError("launcher_item_command_required", $"启动项 {label} 命令行不能为空"));
            }

            if (string.IsNullOrWhiteSpace(item.Key))
            {
                errors.Add(new LauncherConfigError("launcher_item_key_required", $"启动项 {label} 按键不能为空"));
            }

            if (!string.IsNullOrWhiteSpace(item.WorkingDirectory))
            {
                var expanded = Environment.ExpandEnvironmentVariables(item.WorkingDirectory);
                if (!Directory.Exists(expanded))
                {
                    errors.Add(new LauncherConfigError("launcher_item_working_directory_missing", $"启动项 {label} 工作目录不存在"));
                }
            }

            if (!item.Enabled || string.IsNullOrWhiteSpace(item.Key))
            {
                continue;
            }

            var normalizedKey = item.Key.Trim();
            if (enabledKeys.TryGetValue(normalizedKey, out var existing))
            {
                errors.Add(new LauncherConfigError("launcher_item_key_duplicate", $"启动项按键 {normalizedKey} 同时用于 {existing} 和 {label}"));
            }
            else
            {
                enabledKeys[normalizedKey] = label ?? normalizedKey;
            }
        }

        return errors;
    }
}

public sealed record LauncherConfigError(string Error, string Message);
