using System.Runtime.InteropServices;
using System.Windows.Forms;
using H.NotifyIcon.Core;

namespace MaimaiHomeAgent.Tray;

/// <summary>
/// Production <see cref="ITrayIconHost"/> backed by H.NotifyIcon.Core.
/// Must be created and used on the STA UI thread.
/// </summary>
internal sealed class Win32TrayIconHost : ITrayIconHost
{
    private readonly Func<bool> _getAutoStartEnabled;
    private readonly Func<Task> _onOpenLauncher;
    private readonly Func<Task> _onOpenSettings;
    private readonly Func<Task> _onToggleAutoStart;
    private readonly Action _onExit;

    private TrayIconWithContextMenu? _tray;
    private PopupMenuItem? _autoStartItem;
    private IntPtr _hIcon;

    public Win32TrayIconHost(
        Func<bool> getAutoStartEnabled,
        Func<Task> onOpenLauncher,
        Func<Task> onOpenSettings,
        Func<Task> onToggleAutoStart,
        Action onExit)
    {
        _getAutoStartEnabled = getAutoStartEnabled;
        _onOpenLauncher = onOpenLauncher;
        _onOpenSettings = onOpenSettings;
        _onToggleAutoStart = onToggleAutoStart;
        _onExit = onExit;
    }

    public void Create()
    {
        _hIcon = LoadTrayIcon();

        var statusItem = new PopupMenuItem("状态: 运行中", (_, _) => { })
        {
            Enabled = false,
        };

        _autoStartItem = new PopupMenuItem("开机自启", async (_, _) => await _onToggleAutoStart())
        {
            Checked = _getAutoStartEnabled(),
        };

        var launcherItem = new PopupMenuItem("打开应用启动器", async (_, _) => await _onOpenLauncher());
        var settingsItem = new PopupMenuItem("设置", async (_, _) => await _onOpenSettings());
        var exitItem = new PopupMenuItem("退出", (_, _) => _onExit());

        var menu = new PopupMenu();
        menu.Items.Add(statusItem);
        menu.Items.Add(launcherItem);
        menu.Items.Add(settingsItem);
        menu.Items.Add(_autoStartItem);
        menu.Items.Add(exitItem);

        _tray = new TrayIconWithContextMenu
        {
            Icon = _hIcon,
            ToolTip = "Maimai Home Agent",
            ContextMenu = menu,
        };
        _tray.Create();
    }

    public void UpdateAutoStartChecked(bool enabled)
    {
        if (_autoStartItem is not null)
        {
            _autoStartItem.Checked = enabled;
        }
    }

    public void Dispose()
    {
        try { _tray?.Dispose(); } catch { /* best-effort */ }
        _tray = null;

        if (_hIcon != IntPtr.Zero)
        {
            try { DestroyIcon(_hIcon); } catch { /* best-effort */ }
            _hIcon = IntPtr.Zero;
        }
    }

    private static IntPtr LoadTrayIcon()
    {
        var iconPath = Path.Combine(AppContext.BaseDirectory, "Resources", "tray.ico");
        if (File.Exists(iconPath))
        {
            const uint IMAGE_ICON = 1;
            const uint LR_LOADFROMFILE = 0x00000010;
            const uint LR_DEFAULTSIZE = 0x00000040;
            var handle = LoadImageW(
                IntPtr.Zero, iconPath, IMAGE_ICON, 0, 0,
                LR_LOADFROMFILE | LR_DEFAULTSIZE);
            if (handle != IntPtr.Zero) return handle;
        }
        return LoadIconW(IntPtr.Zero, new IntPtr(32512 /* IDI_APPLICATION */));
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadImageW(IntPtr hInst, string lpszName, uint uType, int cxDesired, int cyDesired, uint fuLoad);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadIconW(IntPtr hInstance, IntPtr lpIconName);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr hIcon);
}

/// <summary>
/// Production <see cref="IUiThreadPump"/> that runs a Windows Forms STA
/// message loop on a dedicated background thread.
/// </summary>
internal sealed class WindowsFormsPump : IUiThreadPump
{
    private Thread? _uiThread;
    private SynchronizationContext? _uiContext;

    public void Start(Action onReady)
    {
        var ready = new ManualResetEventSlim(false);
        _uiThread = new Thread(() =>
        {
            try
            {
                _uiContext = new WindowsFormsSynchronizationContext();
                SynchronizationContext.SetSynchronizationContext(_uiContext);
                onReady();
                ready.Set();
                Application.Run();
            }
            catch
            {
                ready.Set();
            }
        })
        {
            IsBackground = true,
            Name = "MaimaiTrayUI",
        };
        _uiThread.SetApartmentState(ApartmentState.STA);
        _uiThread.Start();
        ready.Wait();
    }

    public void RunOnUiThread(Action action)
    {
        _uiContext?.Post(_ => action(), null);
    }

    public void Stop()
    {
        try { _uiContext?.Post(_ => Application.ExitThread(), null); }
        catch { /* best-effort */ }

        if (_uiThread is not null)
        {
            _uiThread.Join(TimeSpan.FromSeconds(2));
            _uiThread = null;
        }
    }
}
