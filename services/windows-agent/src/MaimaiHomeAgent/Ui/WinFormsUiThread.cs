using System.Windows.Forms;

namespace MaimaiHomeAgent.Ui;

internal sealed class WinFormsUiThread : IWinFormsUiThread, IDisposable
{
    private readonly ManualResetEventSlim _ready = new(false);
    private readonly Thread _thread;
    private SynchronizationContext? _context;
    private bool _disposed;

    public WinFormsUiThread()
    {
        _thread = new Thread(Run)
        {
            IsBackground = true,
            Name = "MaimaiWinFormsUI"
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
            throw new ObjectDisposedException(nameof(WinFormsUiThread));
        }

        var context = _context ?? throw new InvalidOperationException("WinForms UI thread is not ready.");
        var completion = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        context.Post(async _ =>
        {
            if (ct.IsCancellationRequested)
            {
                completion.TrySetCanceled(ct);
                return;
            }

            try
            {
                await action().ConfigureAwait(true);
                completion.TrySetResult();
            }
            catch (OperationCanceledException ex) when (ex.CancellationToken == ct)
            {
                completion.TrySetCanceled(ct);
            }
            catch (Exception ex)
            {
                completion.TrySetException(ex);
            }
        }, null);

        return completion.Task;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        try { _context?.Post(_ => Application.ExitThread(), null); }
        catch { /* best-effort */ }
        _thread.Join(TimeSpan.FromSeconds(2));
        _ready.Dispose();
    }

    private void Run()
    {
        try
        {
            _context = new WindowsFormsSynchronizationContext();
            SynchronizationContext.SetSynchronizationContext(_context);
            _ready.Set();
            Application.Run();
        }
        catch
        {
            _ready.Set();
        }
    }
}
