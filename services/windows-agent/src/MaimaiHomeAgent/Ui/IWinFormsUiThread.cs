namespace MaimaiHomeAgent.Ui;

public interface IWinFormsUiThread
{
    Task InvokeAsync(Func<Task> action, CancellationToken ct = default);
}
