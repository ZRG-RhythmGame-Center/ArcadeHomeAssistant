namespace MaimaiHomeAgent.Launcher;

public sealed class LauncherOptions
{
    public bool ShowOnAgentStart { get; set; }

    public int CanvasWidth { get; set; } = 1080;

    public int CanvasHeight { get; set; } = 1920;

    public string? NavigateLeftKey { get; set; } = "Left";

    public string? NavigateRightKey { get; set; } = "Right";

    public string? ConfirmKey { get; set; } = "Enter";

    public string? StopKey { get; set; } = "F11";

    public List<LauncherItemOptions> Items { get; set; } = new();
}

public sealed class LauncherItemOptions
{
    public string? Id { get; set; }

    public string? Name { get; set; }

    public string? Title { get; set; }

    public string? Note { get; set; }

    public string? IconPath { get; set; }

    public string? CommandLine { get; set; }

    public string? WorkingDirectory { get; set; }

    public string? StopCommandLine { get; set; }

    public string? StopWorkingDirectory { get; set; }

    public string? Key { get; set; }

    public int Order { get; set; }

    public bool Enabled { get; set; } = true;
}
