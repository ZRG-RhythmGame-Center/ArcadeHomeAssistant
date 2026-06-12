namespace MaimaiHomeAgent.Tray;

/// <summary>
///     Abstraction over the Win32 tray icon. Allows tests to verify TrayApp
///     behaviour without creating a real NotifyIcon or Win32 window.
/// </summary>
public interface ITrayIconHost : IDisposable
{
    /// <summary>Creates and shows the tray icon with its context menu.</summary>
    void Create();

    /// <summary>Updates the auto-start menu item's checked state.</summary>
    void UpdateAutoStartChecked(bool enabled);
}

/// <summary>
///     Abstraction over the STA UI thread pump. Allows tests to run TrayApp
///     logic synchronously without spinning up a real Windows message loop.
/// </summary>
public interface IUiThreadPump
{
    /// <summary>
    ///     Starts the pump. Invokes <paramref name="onReady" /> once the pump is
    ///     initialised and ready to accept <see cref="RunOnUiThread" /> calls.
    /// </summary>
    void Start(Action onReady);

    /// <summary>Posts an action to run on the UI thread.</summary>
    void RunOnUiThread(Action action);

    /// <summary>Stops the pump and joins the UI thread.</summary>
    void Stop();
}