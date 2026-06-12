using System.Drawing;
using System.Drawing.Imaging;
using System.Windows.Forms;
using MaimaiHomeAgent.Ui;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Launcher;

internal sealed class WinFormsLauncherWindowHost : ILauncherWindowHost
{
    private const float PortraitAspectRatio = 9f / 16f;
    private readonly ILogger<WinFormsLauncherWindowHost> _logger;
    private readonly IWinFormsUiThread _uiThread;
    private Form? _form;
    private PictureBox? _backgroundPicture;
    private IReadOnlyList<LauncherItemRuntime> _items = Array.Empty<LauncherItemRuntime>();
    private Func<string, CancellationToken, Task>? _onKeySelected;
    private bool _isVisible;
    private Panel? _contentPanel;

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

    public Task HideAsync(CancellationToken ct = default) => _uiThread.InvokeAsync(() =>
    {
        if (_form is { IsDisposed: false })
        {
            _form.Hide();
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
        form.Resize += (_, _) => UpdateContentBounds();
        form.KeyDown += async (_, e) =>
        {
            if (e.KeyCode == Keys.Escape)
            {
                e.Handled = true;
                await HideAsync().ConfigureAwait(true);
                return;
            }

            await HandleKeyAsync(e.KeyCode.ToString());
        };

        _contentPanel = new Panel
        {
            BackColor = Color.FromArgb(24, 24, 24)
        };

        _backgroundPicture = new PictureBox
        {
            Dock = DockStyle.Fill,
            BackColor = Color.Black,
            SizeMode = PictureBoxSizeMode.Zoom,
            Image = LoadReferenceImage()
        };
        _contentPanel.Controls.Add(_backgroundPicture);
        form.Controls.Add(_contentPanel);
        UpdateContentBounds();
        return form;
    }

    private void RenderItems()
    {
        if (_form is null || _contentPanel is null)
        {
            return;
        }

        _contentPanel.Controls.Clear();
        if (_backgroundPicture is not null)
        {
            _contentPanel.Controls.Add(_backgroundPicture);
            _backgroundPicture.SendToBack();
        }
    }

    private void UpdateContentBounds()
    {
        if (_form is null || _contentPanel is null)
        {
            return;
        }

        var screenWidth = _form.ClientSize.Width;
        var screenHeight = _form.ClientSize.Height;
        if (screenWidth <= 0 || screenHeight <= 0)
        {
            return;
        }

        var maxHeight = screenHeight;
        var contentWidth = (int)MathF.Round(maxHeight * PortraitAspectRatio);
        if (contentWidth > screenWidth)
        {
            contentWidth = screenWidth;
            maxHeight = (int)MathF.Floor(contentWidth / PortraitAspectRatio);
        }

        var x = (screenWidth - contentWidth) / 2;
        var y = (screenHeight - maxHeight) / 2;
        _contentPanel.Bounds = new Rectangle(x, y, contentWidth, maxHeight);
    }

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

    private static Image? LoadReferenceImage()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "Resources", "GameScreenshot.jpg");
        if (!File.Exists(path))
        {
            return null;
        }

        using var source = Image.FromFile(path);
        var bitmap = new Bitmap(source.Width, source.Height);
        using var graphics = Graphics.FromImage(bitmap);
        using var attributes = new ImageAttributes();
        var matrix = new ColorMatrix
        {
            Matrix33 = 0.2f
        };
        attributes.SetColorMatrix(matrix, ColorMatrixFlag.Default, ColorAdjustType.Bitmap);
        graphics.DrawImage(
            source,
            new Rectangle(0, 0, bitmap.Width, bitmap.Height),
            0,
            0,
            source.Width,
            source.Height,
            GraphicsUnit.Pixel,
            attributes);
        return bitmap;
    }
}
