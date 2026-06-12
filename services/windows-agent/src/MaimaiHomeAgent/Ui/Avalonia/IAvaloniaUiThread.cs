namespace MaimaiHomeAgent.Ui.Avalonia;

public interface IAvaloniaUiThread
{
    Task InvokeAsync(Func<Task> action, CancellationToken ct = default);
}
