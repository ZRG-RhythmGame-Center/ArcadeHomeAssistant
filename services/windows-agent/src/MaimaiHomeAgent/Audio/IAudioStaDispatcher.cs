namespace MaimaiHomeAgent.Audio;

/// <summary>
///     Abstraction over <see cref="AudioStaDispatcher" /> for testability.
///     Allows tests to inject an inline dispatcher without spinning up a real STA thread.
/// </summary>
public interface IAudioStaDispatcher
{
    Task<T> InvokeAsync<T>(Func<Task<T>> work);
    Task InvokeAsync(Func<Task> work);
}