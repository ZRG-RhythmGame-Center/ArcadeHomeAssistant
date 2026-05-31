using System.Threading.Channels;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Audio;

/// <summary>
/// Dispatches audio work onto a single dedicated STA thread.
///
/// AudioSwitcher.AudioApi.CoreAudio is a thin wrapper over Windows Core Audio
/// COM. Some Core Audio interfaces require the calling thread to live in an
/// STA, and reusing the same thread across calls avoids cross-apartment
/// marshalling and recursive COM init costs. We therefore funnel all calls
/// through one long-lived STA worker.
///
/// A bounded <see cref="Channel{T}"/> (capacity 5) gives us flow control:
/// when the queue is full we fail fast with
/// <see cref="AudioServiceBusyException"/> instead of letting the request
/// thread pool back up.
/// </summary>
public sealed class AudioStaDispatcher : IHostedService, IAsyncDisposable, IAudioStaDispatcher
{
    private const int QueueCapacity = 5;

    private readonly ILogger<AudioStaDispatcher> _logger;
    private readonly Channel<WorkItem> _channel;
    private readonly CancellationTokenSource _cts = new();
    private readonly TaskCompletionSource _threadStartedTcs =
        new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly TaskCompletionSource _threadStoppedTcs =
        new(TaskCreationOptions.RunContinuationsAsynchronously);

    private Thread? _staThread;
    private int _disposed;

    public AudioStaDispatcher(ILogger<AudioStaDispatcher> logger)
    {
        _logger = logger;
        _channel = Channel.CreateBounded<WorkItem>(new BoundedChannelOptions(QueueCapacity)
        {
            FullMode = BoundedChannelFullMode.Wait,
            SingleReader = true,
            SingleWriter = false,
        });
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        if (_staThread is not null)
        {
            return Task.CompletedTask;
        }

        _staThread = new Thread(StaThreadLoop)
        {
            Name = "Audio STA Dispatcher",
            IsBackground = true,
        };
        _staThread.SetApartmentState(ApartmentState.STA);
        _staThread.Start();
        _logger.LogInformation("Audio STA dispatcher started.");
        return _threadStartedTcs.Task;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0)
        {
            return Task.CompletedTask;
        }

        // Refuse new writes and tell the worker to stop blocking in
        // WaitToReadAsync.
        _channel.Writer.TryComplete();
        _cts.Cancel();

        // Drain anything still queued and cancel it. The currently in-flight
        // item (if any) is intentionally NOT awaited here: callers that hold
        // a resource the in-flight work depends on may need StopAsync to
        // return so they can release it. The worker exits naturally once it
        // finishes the in-flight item and finds the channel completed.
        while (_channel.Reader.TryRead(out var item))
        {
            item.Completion.TrySetCanceled(cancellationToken);
        }

        _logger.LogInformation("Audio STA dispatcher stop signalled.");
        return Task.CompletedTask;
    }

    /// <summary>
    /// Runs <paramref name="work"/> on the dedicated STA thread and returns
    /// its result. Throws <see cref="AudioServiceBusyException"/> if the
    /// dispatch queue is at capacity.
    /// </summary>
    public async Task<T> InvokeAsync<T>(Func<Task<T>> work)
    {
        ArgumentNullException.ThrowIfNull(work);

        if (_disposed != 0)
        {
            throw new ObjectDisposedException(nameof(AudioStaDispatcher));
        }

        var tcs = new TaskCompletionSource<object?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var item = new WorkItem(async () =>
        {
            var result = await work().ConfigureAwait(false);
            return (object?)result;
        }, tcs);

        if (!_channel.Writer.TryWrite(item))
        {
            throw new AudioServiceBusyException();
        }

        var raw = await tcs.Task.ConfigureAwait(false);
        return (T)raw!;
    }

    /// <summary>
    /// Overload for fire-and-forget style work that returns no value.
    /// </summary>
    public Task InvokeAsync(Func<Task> work)
    {
        ArgumentNullException.ThrowIfNull(work);
        return InvokeAsync<object?>(async () =>
        {
            await work().ConfigureAwait(false);
            return null;
        });
    }

    private void StaThreadLoop()
    {
        try
        {
            _threadStartedTcs.TrySetResult();

            var reader = _channel.Reader;
            while (true)
            {
                WorkItem item;
                try
                {
                    // Synchronous dequeue: blocks the STA thread until an item
                    // arrives or the channel is completed. Using the sync API
                    // here keeps execution pinned to the STA thread.
                    if (!reader.TryRead(out item!))
                    {
                        var waitTask = reader.WaitToReadAsync(_cts.Token).AsTask();
                        var ready = waitTask.GetAwaiter().GetResult();
                        if (!ready)
                        {
                            break;
                        }
                        continue;
                    }
                }
                catch (OperationCanceledException)
                {
                    break;
                }

                try
                {
                    // Run the async work synchronously on this STA thread so
                    // continuations remain on STA. Awaiting the Task here
                    // is fine because callers do not capture a sync context.
                    var resultTask = item.Work();
                    var result = resultTask.GetAwaiter().GetResult();
                    item.Completion.TrySetResult(result);
                }
                catch (Exception ex)
                {
                    item.Completion.TrySetException(ex);
                }
            }

            // Cancel any items that landed after the writer was completed.
            while (reader.TryRead(out var leftover))
            {
                leftover.Completion.TrySetCanceled(_cts.Token);
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Audio STA dispatcher loop crashed.");
            _threadStartedTcs.TrySetException(ex);
        }
        finally
        {
            _threadStoppedTcs.TrySetResult();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed == 0)
        {
            await StopAsync(CancellationToken.None).ConfigureAwait(false);
        }
        _cts.Dispose();
    }

    private readonly record struct WorkItem(
        Func<Task<object?>> Work,
        TaskCompletionSource<object?> Completion);
}
