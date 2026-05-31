using System.Threading;
using System.Threading.Tasks;
using MaimaiHomeAgent.Audio;
using Microsoft.Extensions.Logging.Abstractions;

namespace MaimaiHomeAgent.Tests.Audio;

public class AudioStaDispatcherTests
{
    [Fact]
    public async Task InvokeAsync_RunsWorkOnStaThread()
    {
        await using var dispatcher = new AudioStaDispatcher(NullLogger<AudioStaDispatcher>.Instance);
        await dispatcher.StartAsync(CancellationToken.None);

        var apartment = await dispatcher.InvokeAsync(() =>
        {
            return Task.FromResult(Thread.CurrentThread.GetApartmentState());
        });

        Assert.Equal(ApartmentState.STA, apartment);
    }

    [Fact]
    public async Task InvokeAsync_AllWorkSharesSameManagedThreadId()
    {
        await using var dispatcher = new AudioStaDispatcher(NullLogger<AudioStaDispatcher>.Instance);
        await dispatcher.StartAsync(CancellationToken.None);

        var ids = new int[5];
        for (var i = 0; i < ids.Length; i++)
        {
            var idx = i;
            ids[idx] = await dispatcher.InvokeAsync(() =>
                Task.FromResult(Thread.CurrentThread.ManagedThreadId));
        }

        Assert.All(ids, id => Assert.Equal(ids[0], id));
    }

    [Fact]
    public async Task InvokeAsync_QueueFull_ThrowsAudioServiceBusyException()
    {
        await using var dispatcher = new AudioStaDispatcher(NullLogger<AudioStaDispatcher>.Instance);
        await dispatcher.StartAsync(CancellationToken.None);

        // Pin the STA worker on a long-running item so subsequent items must
        // sit in the channel.
        var release = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
        var workerStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var blocking = dispatcher.InvokeAsync(async () =>
        {
            workerStarted.TrySetResult();
            await release.Task;
            return 0;
        });

        // Wait until the worker is actually executing the blocker so the
        // bounded channel is drained of that first item before we start
        // filling it.
        await workerStarted.Task;

        // Fill the bounded channel (capacity 5).
        var pending = new List<Task<int>>();
        for (var i = 0; i < 5; i++)
        {
            pending.Add(dispatcher.InvokeAsync(() => Task.FromResult(1)));
        }

        // 6th queued item must throw immediately.
        await Assert.ThrowsAsync<AudioServiceBusyException>(async () =>
        {
            await dispatcher.InvokeAsync(() => Task.FromResult(2));
        });

        // Drain so the dispatcher can shut down cleanly.
        release.SetResult(0);
        await blocking;
        await Task.WhenAll(pending);
    }

    [Fact]
    public async Task DisposeAsync_CancelsPendingItems()
    {
        var dispatcher = new AudioStaDispatcher(NullLogger<AudioStaDispatcher>.Instance);
        await dispatcher.StartAsync(CancellationToken.None);

        // Block STA thread.
        var release = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
        var workerStarted = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var blocking = dispatcher.InvokeAsync(async () =>
        {
            workerStarted.TrySetResult();
            await release.Task;
            return 0;
        });
        await workerStarted.Task;

        // Queue an item that will sit in the channel waiting.
        var pending = dispatcher.InvokeAsync(() => Task.FromResult(42));

        // Stop the dispatcher; the queued item must be cancelled.
        await dispatcher.StopAsync(CancellationToken.None);

        // Allow the in-flight item to finish so the worker exits cleanly.
        release.SetResult(0);
        try { await blocking; } catch { /* ignore */ }

        await dispatcher.DisposeAsync();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(async () => await pending);
    }
}
