using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using MaimaiHomeAgent.Settings;

namespace MaimaiHomeAgent.Ui.Avalonia.Settings;

public sealed class SettingsWindowViewModel : INotifyPropertyChanged
{
    private readonly ILogger _logger;
    private readonly IAgentSettingsService _settings;

    private string? _adminPassword;
    private bool _autoStartEnabled;
    private string? _backgroundImagePath;
    private int _canvasHeight = 1920;
    private int _canvasWidth = 1080;
    private string _confirmKey = "Enter";
    private bool _launcherShowOnStart;
    private string _navigateLeftKey = "Left";
    private string _navigateRightKey = "Right";
    private bool _remoteShutdownEnabled;
    private string? _remoteShutdownToken;
    private int _selectedCategoryIndex;
    private string? _statusMessage;
    private string _stopKey = "F11";

    public SettingsWindowViewModel(IAgentSettingsService settings, ILogger logger)
    {
        _settings = settings;
        _logger = logger;
    }

    public string? AdminPassword
    {
        get => _adminPassword;
        set => SetField(ref _adminPassword, value);
    }

    public bool AutoStartEnabled
    {
        get => _autoStartEnabled;
        set => SetField(ref _autoStartEnabled, value);
    }

    public bool LauncherShowOnStart
    {
        get => _launcherShowOnStart;
        set => SetField(ref _launcherShowOnStart, value);
    }

    public string? BackgroundImagePath
    {
        get => _backgroundImagePath;
        set => SetField(ref _backgroundImagePath, value);
    }

    public int CanvasWidth
    {
        get => _canvasWidth;
        set => SetField(ref _canvasWidth, value);
    }

    public int CanvasHeight
    {
        get => _canvasHeight;
        set => SetField(ref _canvasHeight, value);
    }

    public string NavigateLeftKey
    {
        get => _navigateLeftKey;
        set => SetField(ref _navigateLeftKey, value);
    }

    public string NavigateRightKey
    {
        get => _navigateRightKey;
        set => SetField(ref _navigateRightKey, value);
    }

    public string ConfirmKey
    {
        get => _confirmKey;
        set => SetField(ref _confirmKey, value);
    }

    public string StopKey
    {
        get => _stopKey;
        set => SetField(ref _stopKey, value);
    }

    public bool RemoteShutdownEnabled
    {
        get => _remoteShutdownEnabled;
        set => SetField(ref _remoteShutdownEnabled, value);
    }

    public string? RemoteShutdownToken
    {
        get => _remoteShutdownToken;
        set => SetField(ref _remoteShutdownToken, value);
    }

    public string? StatusMessage
    {
        get => _statusMessage;
        set => SetField(ref _statusMessage, value);
    }

    public int SelectedCategoryIndex
    {
        get => _selectedCategoryIndex;
        set => SetField(ref _selectedCategoryIndex, value);
    }

    public ObservableCollection<LauncherItemViewModel> LauncherItems { get; } = new();
    public ObservableCollection<FileRootViewModel> FileRoots { get; } = new();

    public event PropertyChangedEventHandler? PropertyChanged;

    public async Task LoadAsync(CancellationToken ct = default)
    {
        try
        {
            var snapshot = await _settings.GetAsync(ct).ConfigureAwait(true);
            AutoStartEnabled = snapshot.AutoStartEnabled;
            LauncherShowOnStart = snapshot.Launcher.ShowOnAgentStart;
            CanvasWidth = snapshot.Launcher.CanvasWidth;
            CanvasHeight = snapshot.Launcher.CanvasHeight;
            BackgroundImagePath = snapshot.Launcher.BackgroundImagePath;
            NavigateLeftKey = snapshot.Launcher.NavigateLeftKey;
            NavigateRightKey = snapshot.Launcher.NavigateRightKey;
            ConfirmKey = snapshot.Launcher.ConfirmKey;
            StopKey = snapshot.Launcher.StopKey;
            RemoteShutdownEnabled = snapshot.RemoteShutdown.Enabled;
            RemoteShutdownToken = snapshot.RemoteShutdown.ControlToken;

            LauncherItems.Clear();
            foreach (var item in snapshot.Launcher.Items) LauncherItems.Add(LauncherItemViewModel.FromDto(item));

            FileRoots.Clear();
            foreach (var root in snapshot.FileRoots) FileRoots.Add(FileRootViewModel.FromDto(root));

            AdminPassword = null;
            StatusMessage = null;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to load settings.");
            StatusMessage = "加载设置失败。";
        }
    }

    public async Task SaveAsync(CancellationToken ct = default)
    {
        try
        {
            var request = BuildRequest();
            var result = await _settings.UpdateAsync(request, ct).ConfigureAwait(true);
            if (!result.Success)
            {
                StatusMessage = string.Join("\n", result.Errors.Select(e => e.Message));
                return;
            }

            StatusMessage = "设置已保存。";
            AdminPassword = null;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to save settings.");
            StatusMessage = "保存设置失败。";
        }
    }

    public void AddLauncherItem()
    {
        LauncherItems.Add(new LauncherItemViewModel
        {
            Id = Guid.NewGuid().ToString("N")[..8],
            Name = "新启动项",
            Enabled = true
        });
    }

    public void RemoveLauncherItem(LauncherItemViewModel item)
    {
        LauncherItems.Remove(item);
    }

    public void AddFileRoot()
    {
        FileRoots.Add(new FileRootViewModel
        {
            Id = Guid.NewGuid().ToString("N")[..8],
            Name = "新目录"
        });
    }

    public void RemoveFileRoot(FileRootViewModel item)
    {
        FileRoots.Remove(item);
    }

    private AgentSettingsUpdateRequest BuildRequest()
    {
        var launcher = new LauncherSettingsDto(
            LauncherShowOnStart,
            CanvasWidth,
            CanvasHeight,
            BackgroundImagePath,
            NavigateLeftKey,
            NavigateRightKey,
            ConfirmKey,
            StopKey,
            LauncherItems.Select((item, index) => item.ToDto(index)).ToList());

        var fileRoots = FileRoots.Select(r => r.ToDto()).ToList();

        var remoteShutdown = new RemoteShutdownSettingsDto(RemoteShutdownEnabled, RemoteShutdownToken);

        return new AgentSettingsUpdateRequest(
            string.IsNullOrWhiteSpace(AdminPassword) ? null : AdminPassword,
            AutoStartEnabled,
            launcher,
            fileRoots,
            remoteShutdown);
    }

    private void SetField<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

public sealed class LauncherItemViewModel : INotifyPropertyChanged
{
    private string _commandLine = "";
    private bool _enabled = true;
    private string? _iconPath;
    private string _id = "";
    private string _key = "";
    private string _name = "";
    private string? _note;
    private int _orderIndex;
    private string _stopCommandLine = "";
    private string? _stopWorkingDirectory;
    private string? _workingDirectory;

    public string Id
    {
        get => _id;
        set
        {
            _id = value;
            OnChanged();
        }
    }

    public string Name
    {
        get => _name;
        set
        {
            _name = value;
            OnChanged();
        }
    }

    public string? Note
    {
        get => _note;
        set
        {
            _note = value;
            OnChanged();
        }
    }

    public string? IconPath
    {
        get => _iconPath;
        set
        {
            _iconPath = value;
            OnChanged();
        }
    }

    public string CommandLine
    {
        get => _commandLine;
        set
        {
            _commandLine = value;
            OnChanged();
        }
    }

    public string? WorkingDirectory
    {
        get => _workingDirectory;
        set
        {
            _workingDirectory = value;
            OnChanged();
        }
    }

    public string StopCommandLine
    {
        get => _stopCommandLine;
        set
        {
            _stopCommandLine = value;
            OnChanged();
        }
    }

    public string? StopWorkingDirectory
    {
        get => _stopWorkingDirectory;
        set
        {
            _stopWorkingDirectory = value;
            OnChanged();
        }
    }

    public string Key
    {
        get => _key;
        set
        {
            _key = value;
            OnChanged();
        }
    }

    public int OrderIndex
    {
        get => _orderIndex;
        set
        {
            _orderIndex = value;
            OnChanged();
        }
    }

    public bool Enabled
    {
        get => _enabled;
        set
        {
            _enabled = value;
            OnChanged();
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public LauncherItemSettingsDto ToDto(int order)
    {
        return new LauncherItemSettingsDto(
            Id, Name, Name, Note, IconPath,
            CommandLine, WorkingDirectory,
            StopCommandLine, StopWorkingDirectory,
            Key, OrderIndex, Enabled);
    }

    public static LauncherItemViewModel FromDto(LauncherItemSettingsDto dto)
    {
        return new LauncherItemViewModel
        {
            Id = dto.Id,
            Name = dto.Name,
            Note = dto.Note,
            IconPath = dto.IconPath,
            CommandLine = dto.CommandLine,
            WorkingDirectory = dto.WorkingDirectory,
            StopCommandLine = dto.StopCommandLine,
            StopWorkingDirectory = dto.StopWorkingDirectory,
            Key = dto.Key,
            OrderIndex = dto.Order,
            Enabled = dto.Enabled
        };
    }

    private void OnChanged([CallerMemberName] string? name = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

public sealed class FileRootViewModel : INotifyPropertyChanged
{
    private string _id = "";
    private string _name = "";
    private string _path = "";
    private bool _readOnly;

    public string Id
    {
        get => _id;
        set
        {
            _id = value;
            OnChanged();
        }
    }

    public string Name
    {
        get => _name;
        set
        {
            _name = value;
            OnChanged();
        }
    }

    public string Path
    {
        get => _path;
        set
        {
            _path = value;
            OnChanged();
        }
    }

    public bool ReadOnly
    {
        get => _readOnly;
        set
        {
            _readOnly = value;
            OnChanged();
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public FileRootSettingsDto ToDto()
    {
        return new FileRootSettingsDto(Id, Name, Path, ReadOnly);
    }

    public static FileRootViewModel FromDto(FileRootSettingsDto dto)
    {
        return new FileRootViewModel
        {
            Id = dto.Id,
            Name = dto.Name,
            Path = dto.Path,
            ReadOnly = dto.ReadOnly
        };
    }

    private void OnChanged([CallerMemberName] string? name = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
