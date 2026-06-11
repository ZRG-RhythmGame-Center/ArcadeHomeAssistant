using System.Drawing;
using System.Windows.Forms;
using MaimaiHomeAgent.Ui;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Launcher;

internal sealed class WinFormsLauncherWindowHost : ILauncherWindowHost
{
    private readonly ILogger<WinFormsLauncherWindowHost> _logger;
    private readonly IWinFormsUiThread _uiThread;
    private Form? _form;
    private IReadOnlyList<LauncherItemRuntime> _items = Array.Empty<LauncherItemRuntime>();
    private Func<string, CancellationToken, Task>? _onKeySelected;
    private bool _isVisible;

    public WinFormsLauncherWindowHost(
        IWinFormsUiThread uiThread,
        ILogger<WinFormsLauncherWindowHost> logger)
    {
        _uiThread = uiThread;
        _logger = logger;
    }

    public bool IsVisible => _isVisible;

    public Task ShowAsync(IReadOnlyList<LauncherItemRuntime> items, Func<string, CancellationToken, Task> onKeySelected, CancellationToken ct = default) =>
        _uiThread.InvokeAsync(() =>
    {
        _items = items;
        _onKeySelected = onKeySelected;

        if (_form is null || _form.IsDisposed)
        {
            _form = CreateForm();
        }

        RenderItems();
        _form.WindowState = FormWindowState.Normal;
        _form.Show();
        _form.Activate();
        _isVisible = true;
        return Task.CompletedTask;
    }, ct);

    public Task MinimizeAsync(CancellationToken ct = default) => _uiThread.InvokeAsync(() =>
    {
        if (_form is { IsDisposed: false })
        {
            _form.WindowState = FormWindowState.Minimized;
        }

        _isVisible = false;
        return Task.CompletedTask;
    }, ct);

    private Form CreateForm()
    {
        var form = new Form
        {
            Text = "Maimai Launcher",
            FormBorderStyle = FormBorderStyle.None,
            WindowState = FormWindowState.Maximized,
            StartPosition = FormStartPosition.Manual,
            BackColor = Color.Black,
            ForeColor = Color.White,
            KeyPreview = true,
            TopMost = true
        };
        form.Bounds = Screen.PrimaryScreen?.Bounds ?? new Rectangle(0, 0, 1080, 1920);
        form.FormClosed += (_, _) => _isVisible = false;
        form.KeyDown += async (_, e) => await HandleKeyAsync(e.KeyCode.ToString());
        return form;
    }

    private void RenderItems()
    {
        if (_form is null)
        {
            return;
        }

        _form.Controls.Clear();
        var panel = new FlowLayoutPanel
        {
            Dock = DockStyle.Bottom,
            Height = Math.Max(320, _form.Height / 2),
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            Padding = new Padding(48),
            BackColor = Color.FromArgb(24, 24, 24),
            AutoScroll = true
        };

        if (_items.Count == 0)
        {
            panel.Controls.Add(CreateLabel("暂无启动项", 32));
        }
        else
        {
            foreach (var item in _items)
            {
                panel.Controls.Add(CreateLabel($"[{item.Key}] {item.Name}", 30));
            }
        }

        _form.Controls.Add(panel);
    }

    private static Label CreateLabel(string text, int size) => new()
    {
        AutoSize = true,
        ForeColor = Color.White,
        Font = new Font("Microsoft YaHei UI", size, FontStyle.Bold),
        Margin = new Padding(0, 12, 0, 12),
        Text = text
    };

    private async Task HandleKeyAsync(string key)
    {
        var item = _items.FirstOrDefault(candidate => string.Equals(candidate.Key, key, StringComparison.OrdinalIgnoreCase));
        if (item is null || _onKeySelected is null)
        {
            return;
        }

        try
        {
            await _onKeySelected(item.Id, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Launcher key handler failed for {ItemId}.", item.Id);
        }
    }
}
