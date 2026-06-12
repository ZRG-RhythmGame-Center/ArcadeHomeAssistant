using System.Runtime.InteropServices;
using H.NotifyIcon.Core;

namespace MaimaiHomeAgent.Tray;

/// <summary>
///     Production <see cref="ITrayIconHost" /> backed by H.NotifyIcon.Core.
///     Must be created and used on the STA UI thread.
/// </summary>
internal sealed class Win32TrayIconHost : ITrayIconHost
{
    private readonly Func<bool> _getAutoStartEnabled;
    private readonly Action _onExit;
    private readonly Func<Task> _onOpenLauncher;
    private readonly Func<Task> _onOpenSettings;
    private readonly Func<Task> _onToggleAutoStart;
    private PopupMenuItem? _autoStartItem;
    private IntPtr _hIcon;

    private TrayIconWithContextMenu? _tray;

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
            Enabled = false
        };

        _autoStartItem = new PopupMenuItem("开机自启", async (_, _) => await _onToggleAutoStart())
        {
            Checked = _getAutoStartEnabled()
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
            ContextMenu = menu
        };
        _tray.Create();
    }

    public void UpdateAutoStartChecked(bool enabled)
    {
        if (_autoStartItem is not null) _autoStartItem.Checked = enabled;
    }

    public void Dispose()
    {
        try
        {
            _tray?.Dispose();
        }
        catch
        {
            /* best-effort */
        }

        _tray = null;

        if (_hIcon != IntPtr.Zero)
        {
            try
            {
                DestroyIcon(_hIcon);
            }
            catch
            {
                /* best-effort */
            }

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
    private static extern IntPtr LoadImageW(IntPtr hInst, string lpszName, uint uType, int cxDesired, int cyDesired,
        uint fuLoad);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr LoadIconW(IntPtr hInstance, IntPtr lpIconName);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyIcon(IntPtr hIcon);
}

/// <summary>
///     Production <see cref="IUiThreadPump" /> that runs a native Win32 message
///     loop on a dedicated STA background thread. No WinForms dependency.
/// </summary>
internal sealed class Win32MessagePump : IUiThreadPump
{
    private const uint WM_QUIT = 0x0012;
    private volatile bool _running;
    private uint _threadId;
    private Thread? _uiThread;

    public void Start(Action onReady)
    {
        var ready = new ManualResetEventSlim(false);
        _running = true;
        _uiThread = new Thread(() =>
        {
            try
            {
                _threadId = GetCurrentThreadId();
                onReady();
                ready.Set();
                RunMessageLoop();
            }
            catch
            {
                ready.Set();
            }
        })
        {
            IsBackground = true,
            Name = "MaimaiTrayUI"
        };
        _uiThread.SetApartmentState(ApartmentState.STA);
        _uiThread.Start();
        ready.Wait();
    }

    public void RunOnUiThread(Action action)
    {
        // Not needed for current tray usage but satisfies the interface.
        // If needed in the future, use PostMessage + a custom window message.
        ThreadPool.QueueUserWorkItem(_ => action());
    }

    public void Stop()
    {
        _running = false;
        if (_threadId != 0) PostThreadMessageW(_threadId, WM_QUIT, IntPtr.Zero, IntPtr.Zero);

        if (_uiThread is not null)
        {
            _uiThread.Join(TimeSpan.FromSeconds(2));
            _uiThread = null;
        }
    }

    private void RunMessageLoop()
    {
        while (_running)
        {
            var result = GetMessageW(out var msg, IntPtr.Zero, 0, 0);
            if (result == 0 || result == -1) break;

            TranslateMessage(ref msg);
            DispatchMessageW(ref msg);
        }
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetMessageW(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref MSG lpMsg);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr DispatchMessageW(ref MSG lpMsg);

    [DllImport("user32.dll")]
    private static extern bool PostThreadMessageW(uint idThread, uint Msg, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public POINT pt;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int x;
        public int y;
    }
}