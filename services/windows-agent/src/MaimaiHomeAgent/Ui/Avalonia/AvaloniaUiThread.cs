using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Threading;

namespace MaimaiHomeAgent.Ui.Avalonia;

internal sealed class AvaloniaUiThread : IAvaloniaUiThread, IDisposable
{
    private readonly ManualResetEventSlim _ready = new(false);
    private readonly Thread _thread;
    private bool _disposed;

    public AvaloniaUiThread()
    {
        _thread = new Thread(Run)
        {
            IsBackground = true,
            Name = "MaimaiAvaloniaUI"
        };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
        _ready.Wait();
    }

    public Task InvokeAsync(Func<Task> action, CancellationToken ct = default)
    {
        ArgumentNullException.ThrowIfNull(action);
        if (_disposed)
        {
            throw new ObjectDisposedException(nameof(AvaloniaUiThread));
        }

        var tcs = new TaskCompletionSource();
        Dispatcher.UIThread.Post(async () =>
        {
            try
            {
                ct.ThrowIfCancellationRequested();
                await action().ConfigureAwait(true);
                tcs.TrySetResult();
            }
            catch (Exception ex)
            {
                tcs.TrySetException(ex);
            }
        });
        return tcs.Task;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        try
        {
            Dispatcher.UIThread.Post(() =>
            {
                if (Application.Current?.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop)
                {
                    desktop.Shutdown();
                }
            });
        }
        catch
        {
            // best-effort shutdown
        }

        _thread.Join(TimeSpan.FromSeconds(2));
        _ready.Dispose();
    }

    private void Run()
    {
        try
        {
            BuildAvaloniaApp()
                .SetupWithLifetime(new ClassicDesktopStyleApplicationLifetime
                {
                    ShutdownMode = ShutdownMode.OnExplicitShutdown
                });
            _ready.Set();
            Dispatcher.UIThread.MainLoop(CancellationToken.None);
        }
        catch
        {
            _ready.Set();
        }
    }

    private static AppBuilder BuildAvaloniaApp() =>
        AppBuilder.Configure<AvaloniaApp>()
            .UsePlatformDetect()
            .LogToTrace();
}
