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
    private const int GWLP_USERDATA = -21;
    private const int PM_REMOVE = 0x0001;
    private const int RIDEV_INPUTSINK = 0x00000100;
    private const int RID_INPUT = 0x10000003;
    private const int RIM_TYPEKEYBOARD = 1;
    private const int WH_KEYBOARD = 2;
    private const uint WM_CREATE = 0x0001;
    private const uint WM_DESTROY = 0x0002;
    private const uint WM_INPUT = 0x00FF;
    private const uint WM_QUIT = 0x0012;
    private int? _stopShortcutVirtualKey;
    private volatile bool _running;
    private IntPtr _messageWindow;
    private Action? _onStopShortcut;
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
                _messageWindow = RawInputMessageWindow.Create(this);
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

    public void RegisterStopShortcut(string key, Action onStopShortcut)
    {
        _onStopShortcut = onStopShortcut;
        var virtualKey = ResolveVirtualKey(key);
        if (virtualKey is null) return;
        _stopShortcutVirtualKey = virtualKey.Value;
    }

    public void Stop()
    {
        _running = false;
        if (_messageWindow != IntPtr.Zero) DestroyWindow(_messageWindow);
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

    private void HandleRawInput(IntPtr lParam)
    {
        if (_stopShortcutVirtualKey is not { } targetKey) return;

        var size = 0;
        GetRawInputData(lParam, RID_INPUT, IntPtr.Zero, ref size, Marshal.SizeOf<RAWINPUTHEADER>());
        if (size <= 0) return;

        var buffer = Marshal.AllocHGlobal(size);
        try
        {
            var read = GetRawInputData(lParam, RID_INPUT, buffer, ref size, Marshal.SizeOf<RAWINPUTHEADER>());
            if (read != size) return;

            var input = Marshal.PtrToStructure<RAWINPUT>(buffer);
            if (input.header.dwType != RIM_TYPEKEYBOARD) return;

            // Message 0x0100 is WM_KEYDOWN; 0x0104 is WM_SYSKEYDOWN.
            if (input.keyboard.Message is not (0x0100 or 0x0104)) return;
            if (input.keyboard.VKey != targetKey) return;

            ThreadPool.QueueUserWorkItem(_ => _onStopShortcut?.Invoke());
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    private static int? ResolveVirtualKey(string key)
    {
        return key.Trim().ToUpperInvariant() switch
        {
            "F1" => 0x70,
            "F2" => 0x71,
            "F3" => 0x72,
            "F4" => 0x73,
            "F5" => 0x74,
            "F6" => 0x75,
            "F7" => 0x76,
            "F8" => 0x77,
            "F9" => 0x78,
            "F10" => 0x79,
            "F11" => 0x7A,
            "F12" => 0x7B,
            "BACKSPACE" => 0x08,
            "DELETE" => 0x2E,
            "ESCAPE" => 0x1B,
            _ => null
        };
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

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyWindow(IntPtr hWnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint GetRawInputData(IntPtr hRawInput, int uiCommand, IntPtr pData, ref int pcbSize,
        int cbSizeHeader);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterRawInputDevices(RAWINPUTDEVICE[] pRawInputDevices, uint uiNumDevices,
        uint cbSize);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern ushort RegisterClassW(ref WNDCLASS lpWndClass);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowExW(
        int dwExStyle,
        string lpClassName,
        string lpWindowName,
        int dwStyle,
        int x,
        int y,
        int nWidth,
        int nHeight,
        IntPtr hWndParent,
        IntPtr hMenu,
        IntPtr hInstance,
        IntPtr lpParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr DefWindowProcW(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowLongPtrW(IntPtr hWnd, int nIndex, IntPtr dwNewLong);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr GetWindowLongPtrW(IntPtr hWnd, int nIndex);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr GetModuleHandleW(string? lpModuleName);

    private delegate IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);

    private sealed class RawInputMessageWindow
    {
        private static readonly WndProc WindowProc = WndProc;
        private const string ClassName = "MaimaiHomeAgentRawInputWindow";

        public static IntPtr Create(Win32MessagePump owner)
        {
            var instance = GetModuleHandleW(null);
            var wc = new WNDCLASS
            {
                lpfnWndProc = WindowProc,
                hInstance = instance,
                lpszClassName = ClassName
            };
            RegisterClassW(ref wc);

            var handle = GCHandle.Alloc(owner);
            var window = CreateWindowExW(0, ClassName, ClassName, 0, 0, 0, 0, 0, IntPtr.Zero, IntPtr.Zero, instance,
                GCHandle.ToIntPtr(handle));
            if (window == IntPtr.Zero) handle.Free();
            return window;
        }

        private static IntPtr WndProc(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam)
        {
            if (msg == WM_CREATE)
            {
                var createStruct = Marshal.PtrToStructure<CREATESTRUCT>(lParam);
                SetWindowLongPtrW(hWnd, GWLP_USERDATA, createStruct.lpCreateParams);
                var devices = new[]
                {
                    new RAWINPUTDEVICE
                    {
                        usUsagePage = 0x01,
                        usUsage = 0x06,
                        dwFlags = RIDEV_INPUTSINK,
                        hwndTarget = hWnd
                    }
                };
                RegisterRawInputDevices(devices, 1, (uint)Marshal.SizeOf<RAWINPUTDEVICE>());
            }
            else if (msg == WM_INPUT)
            {
                var owner = GetOwner(hWnd);
                owner?.HandleRawInput(lParam);
            }
            else if (msg == WM_DESTROY)
            {
                var handle = GetWindowLongPtrW(hWnd, GWLP_USERDATA);
                if (handle != IntPtr.Zero)
                {
                    GCHandle.FromIntPtr(handle).Free();
                    SetWindowLongPtrW(hWnd, GWLP_USERDATA, IntPtr.Zero);
                }
            }

            return DefWindowProcW(hWnd, msg, wParam, lParam);
        }

        private static Win32MessagePump? GetOwner(IntPtr hWnd)
        {
            var handle = GetWindowLongPtrW(hWnd, GWLP_USERDATA);
            return handle == IntPtr.Zero ? null : GCHandle.FromIntPtr(handle).Target as Win32MessagePump;
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WNDCLASS
    {
        public uint style;
        public WndProc lpfnWndProc;
        public int cbClsExtra;
        public int cbWndExtra;
        public IntPtr hInstance;
        public IntPtr hIcon;
        public IntPtr hCursor;
        public IntPtr hbrBackground;
        public string? lpszMenuName;
        public string lpszClassName;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CREATESTRUCT
    {
        public IntPtr lpCreateParams;
        public IntPtr hInstance;
        public IntPtr hMenu;
        public IntPtr hwndParent;
        public int cy;
        public int cx;
        public int y;
        public int x;
        public int style;
        public IntPtr lpszName;
        public IntPtr lpszClass;
        public int dwExStyle;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RAWINPUTDEVICE
    {
        public ushort usUsagePage;
        public ushort usUsage;
        public int dwFlags;
        public IntPtr hwndTarget;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RAWINPUTHEADER
    {
        public int dwType;
        public int dwSize;
        public IntPtr hDevice;
        public IntPtr wParam;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RAWINPUT
    {
        public RAWINPUTHEADER header;
        public RAWKEYBOARD keyboard;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RAWKEYBOARD
    {
        public ushort MakeCode;
        public ushort Flags;
        public ushort Reserved;
        public ushort VKey;
        public uint Message;
        public uint ExtraInformation;
    }

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
