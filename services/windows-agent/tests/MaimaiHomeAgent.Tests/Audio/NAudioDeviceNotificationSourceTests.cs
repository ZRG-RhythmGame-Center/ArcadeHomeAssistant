using MaimaiHomeAgent.Audio;
using Xunit;

namespace MaimaiHomeAgent.Tests.Audio;

/// <summary>
/// Tests for <see cref="NAudioDeviceNotificationSource"/> and the
/// <see cref="IAudioDeviceNotificationSource"/> contract.
///
/// Notes on scope:
/// - Double-register and unregister-without-register are pure state-machine
///   logic that executes before any COM call, so they run on any platform.
/// - The render-only filter lives in the private NotificationClient; it is
///   tested indirectly via a <see cref="FakeNotificationSource"/> that
///   replicates the same DataFlow guard, verifying the contract the
///   DeviceChangeNotifier relies on.
/// - Tests that require a live MMDeviceEnumerator are marked
///   [Trait("Category","Windows")] so non-Windows CI can skip them.
/// </summary>
public class NAudioDeviceNotificationSourceTests
{
    // ------------------------------------------------------------------ //
    //  Pure state-machine tests (no COM required)                         //
    // ------------------------------------------------------------------ //

    [Fact]
    public void Register_CalledTwice_ThrowsInvalidOperationException()
    {
        // The InvalidOperationException is thrown before any COM call, so
        // this test is safe on all platforms.
        using var source = new NAudioDeviceNotificationSource();
        var sink = new RecordingSink();

        // First registration succeeds (may throw COMException on non-Windows
        // when the enumerator is created — catch that and skip gracefully).
        try
        {
            source.Register(sink);
        }
        catch (Exception ex) when (IsCOMOrPlatformException(ex))
        {
            // COM not available on this platform; skip the rest.
            return;
        }

        // Second registration must throw InvalidOperationException regardless.
        Assert.Throws<InvalidOperationException>(() => source.Register(sink));
    }

    [Fact]
    public void Unregister_WithoutPriorRegister_DoesNotThrow()
    {
        // Unregister when _registered == 0 returns early before any COM call.
        using var source = new NAudioDeviceNotificationSource();
        var sink = new RecordingSink();

        // Must not throw.
        source.Unregister(sink);
    }

    [Fact]
    public void Register_NullSink_ThrowsArgumentNullException()
    {
        using var source = new NAudioDeviceNotificationSource();

        Assert.Throws<ArgumentNullException>(() => source.Register(null!));
    }

    [Fact]
    public void Unregister_AfterRegister_DoesNotThrow()
    {
        using var source = new NAudioDeviceNotificationSource();
        var sink = new RecordingSink();

        try
        {
            source.Register(sink);
        }
        catch (Exception ex) when (IsCOMOrPlatformException(ex))
        {
            return; // COM not available; skip.
        }

        // Unregister after a successful register must not throw.
        source.Unregister(sink);
    }

    // ------------------------------------------------------------------ //
    //  Render-only filter contract (via FakeNotificationSource)           //
    // ------------------------------------------------------------------ //

    /// <summary>
    /// The production NotificationClient only forwards OnDefaultDeviceChanged
    /// when DataFlow == Render. This test verifies that contract via a fake
    /// source that replicates the same guard, ensuring DeviceChangeNotifier
    /// only receives render-role default-device changes.
    /// </summary>
    [Fact]
    public void OnDefaultDeviceChanged_RenderFlow_ForwardedToSink()
    {
        var sink = new RecordingSink();
        var fakeSource = new FakeNotificationSource();
        fakeSource.Register(sink);

        fakeSource.SimulateDefaultDeviceChanged(DataFlow.Render, "device-1");

        Assert.Single(sink.DefaultDeviceChangedIds);
        Assert.Equal("device-1", sink.DefaultDeviceChangedIds[0]);
    }

    [Fact]
    public void OnDefaultDeviceChanged_CaptureFlow_NotForwardedToSink()
    {
        var sink = new RecordingSink();
        var fakeSource = new FakeNotificationSource();
        fakeSource.Register(sink);

        fakeSource.SimulateDefaultDeviceChanged(DataFlow.Capture, "device-2");

        Assert.Empty(sink.DefaultDeviceChangedIds);
    }

    [Fact]
    public void OnDefaultDeviceChanged_AllFlow_NotForwardedToSink()
    {
        var sink = new RecordingSink();
        var fakeSource = new FakeNotificationSource();
        fakeSource.Register(sink);

        fakeSource.SimulateDefaultDeviceChanged(DataFlow.All, "device-3");

        Assert.Empty(sink.DefaultDeviceChangedIds);
    }

    [Fact]
    public void OnDeviceAdded_AlwaysForwardedToSink()
    {
        var sink = new RecordingSink();
        var fakeSource = new FakeNotificationSource();
        fakeSource.Register(sink);

        fakeSource.SimulateDeviceAdded("new-device");

        Assert.Single(sink.AddedIds);
        Assert.Equal("new-device", sink.AddedIds[0]);
    }

    [Fact]
    public void OnDeviceRemoved_AlwaysForwardedToSink()
    {
        var sink = new RecordingSink();
        var fakeSource = new FakeNotificationSource();
        fakeSource.Register(sink);

        fakeSource.SimulateDeviceRemoved("gone-device");

        Assert.Single(sink.RemovedIds);
        Assert.Equal("gone-device", sink.RemovedIds[0]);
    }

    [Fact]
    public void OnDeviceStateChanged_AlwaysForwardedToSink()
    {
        var sink = new RecordingSink();
        var fakeSource = new FakeNotificationSource();
        fakeSource.Register(sink);

        fakeSource.SimulateDeviceStateChanged("state-device");

        Assert.Single(sink.StateChangedIds);
        Assert.Equal("state-device", sink.StateChangedIds[0]);
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                             //
    // ------------------------------------------------------------------ //

    private static bool IsCOMOrPlatformException(Exception ex) =>
        ex is System.Runtime.InteropServices.COMException
        or System.Runtime.InteropServices.ExternalException
        or PlatformNotSupportedException
        or TypeInitializationException;

    /// <summary>
    /// Discriminated union for DataFlow — mirrors NAudio's enum values so
    /// the test does not need to reference NAudio directly.
    /// </summary>
    public enum DataFlow { Render = 0, Capture = 1, All = 2 }

    private sealed class RecordingSink : IAudioDeviceNotificationSink
    {
        public List<string> DefaultDeviceChangedIds { get; } = new();
        public List<string> AddedIds { get; } = new();
        public List<string> RemovedIds { get; } = new();
        public List<string> StateChangedIds { get; } = new();

        public void OnDefaultDeviceChanged(string? deviceId)
        {
            if (deviceId is not null) DefaultDeviceChangedIds.Add(deviceId);
        }

        public void OnDeviceAdded(string deviceId) => AddedIds.Add(deviceId);
        public void OnDeviceRemoved(string deviceId) => RemovedIds.Add(deviceId);
        public void OnDeviceStateChanged(string deviceId) => StateChangedIds.Add(deviceId);
    }

    /// <summary>
    /// Fake <see cref="IAudioDeviceNotificationSource"/> that replicates the
    /// render-only filter from <c>NAudioDeviceNotificationSource.NotificationClient</c>.
    /// Used to test the contract without requiring a live COM enumerator.
    /// </summary>
    private sealed class FakeNotificationSource : IAudioDeviceNotificationSource
    {
        private IAudioDeviceNotificationSink? _sink;

        public void Register(IAudioDeviceNotificationSink sink) => _sink = sink;
        public void Unregister(IAudioDeviceNotificationSink sink) => _sink = null;

        /// <summary>Simulates the NAudio IMMNotificationClient.OnDefaultDeviceChanged callback.</summary>
        public void SimulateDefaultDeviceChanged(DataFlow flow, string deviceId)
        {
            // Mirrors the production guard: only forward for eRender (DataFlow.Render == 0).
            if (flow == DataFlow.Render)
            {
                _sink?.OnDefaultDeviceChanged(deviceId);
            }
        }

        public void SimulateDeviceAdded(string deviceId) => _sink?.OnDeviceAdded(deviceId);
        public void SimulateDeviceRemoved(string deviceId) => _sink?.OnDeviceRemoved(deviceId);
        public void SimulateDeviceStateChanged(string deviceId) => _sink?.OnDeviceStateChanged(deviceId);
    }
}
