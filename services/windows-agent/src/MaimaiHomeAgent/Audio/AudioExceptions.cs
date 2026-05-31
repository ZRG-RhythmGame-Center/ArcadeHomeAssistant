namespace MaimaiHomeAgent.Audio;

/// <summary>
/// Thrown when the dedicated audio STA dispatcher's bounded queue is full and
/// a new request cannot be accepted. Maps to HTTP 503 in API layers.
/// </summary>
public sealed class AudioServiceBusyException : Exception
{
    public AudioServiceBusyException()
        : base("The audio dispatcher is busy. Try again shortly.")
    {
    }

    public AudioServiceBusyException(string message)
        : base(message)
    {
    }

    public AudioServiceBusyException(string message, Exception innerException)
        : base(message, innerException)
    {
    }
}

/// <summary>
/// Domain wrapper for failures originating in Core Audio COM calls. The
/// underlying <see cref="System.Runtime.InteropServices.COMException"/> (if
/// any) is preserved as <see cref="Exception.InnerException"/>.
/// </summary>
public sealed class AudioOperationException : Exception
{
    public AudioOperationException(string message)
        : base(message)
    {
    }

    public AudioOperationException(string message, Exception innerException)
        : base(message, innerException)
    {
    }
}

/// <summary>
/// Thrown when a requested audio device id is unknown or unavailable.
/// </summary>
public sealed class AudioDeviceNotFoundException : Exception
{
    public AudioDeviceNotFoundException(Guid deviceId)
        : base($"No audio device with id {deviceId} was found.")
    {
        DeviceId = deviceId;
    }

    public Guid DeviceId { get; }
}
