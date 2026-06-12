using System.Drawing;
using System.Drawing.Imaging;
using System.Windows.Forms;
using MaimaiHomeAgent.Ui;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Launcher;

internal sealed class WinFormsLauncherWindowHost : ILauncherWindowHost
{
    private const float PortraitAspectRatio = 9f / 16f;
    private const int CardWidth = 220;
    private const int CardHeight = 300;
    private const int CardGap = 20;
    private readonly ILogger<WinFormsLauncherWindowHost> _logger;
    private readonly IWinFormsUiThread _uiThread;
    private Form? _form;
    private PictureBox? _backgroundPicture;
    private IReadOnlyList<LauncherItemRuntime> _items = Array.Empty<LauncherItemRuntime>();
    private Func<string, CancellationToken, Task>? _onKeySelected;
    private bool _isVisible;
    private Panel? _contentPanel;
    private Panel? _cardsHost;
    private LauncherNavigationOptions _navigation = LauncherNavigationOptions.Default;
    private int _selectedIndex;

    public WinFormsLauncherWindowHost(
        IWinFormsUiThread uiThread,
        ILogger<WinFormsLauncherWindowHost> logger)
    {
        _uiThread = uiThread;
        _logger = logger;
    }

    public bool IsVisible => _isVisible;

    public Task ShowAsync(
        IReadOnlyList<LauncherItemRuntime> items,
        LauncherNavigationOptions navigation,
        Func<string, CancellationToken, Task> onKeySelected,
        CancellationToken ct = default) =>
        _uiThread.InvokeAsync(() =>
    {
        _items = items;
        _navigation = navigation;
        _onKeySelected = onKeySelected;
        if (_selectedIndex >= _items.Count)
        {
            _selectedIndex = Math.Max(0, _items.Count - 1);
        }

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

            await HandleKeyAsync(e.KeyCode).ConfigureAwait(true);
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

        _cardsHost = new Panel
        {
            BackColor = Color.Transparent,
            Bounds = BuildCardsHostBounds()
        };
        _contentPanel.Controls.Add(_cardsHost);
        _cardsHost.BringToFront();
        RenderCards();
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
        if (_cardsHost is not null)
        {
            _cardsHost.Bounds = BuildCardsHostBounds();
            RenderCards();
        }
    }

    private async Task HandleKeyAsync(Keys key)
    {
        if (MatchesKey(key, _navigation.NavigateLeftKey))
        {
            MoveSelection(-1);
            return;
        }

        if (MatchesKey(key, _navigation.NavigateRightKey))
        {
            MoveSelection(1);
            return;
        }

        if (!MatchesKey(key, _navigation.ConfirmKey) || _onKeySelected is null || _items.Count == 0)
        {
            return;
        }

        var item = _items[_selectedIndex];
        try
        {
            await _onKeySelected(item.Id, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Launcher key handler failed for {ItemId}.", item.Id);
        }
    }

    private void MoveSelection(int delta)
    {
        if (_items.Count == 0)
        {
            return;
        }

        _selectedIndex = (_selectedIndex + delta + _items.Count) % _items.Count;
        RenderCards();
    }

    private void RenderCards()
    {
        if (_cardsHost is null)
        {
            return;
        }

        _cardsHost.Controls.Clear();
        if (_items.Count == 0)
        {
            return;
        }

        var centerX = _cardsHost.Width / 2;
        var centerY = _cardsHost.Height / 2;
        for (var index = 0; index < _items.Count; index++)
        {
            var relative = index - _selectedIndex;
            var scale = index == _selectedIndex ? 1.15f : 0.92f;
            var width = (int)MathF.Round(CardWidth * scale);
            var height = (int)MathF.Round(CardHeight * scale);
            var x = centerX - (width / 2) + relative * (CardWidth + CardGap);
            var y = centerY - (height / 2);
            var card = CreateCard(_items[index], index == _selectedIndex, width, height);
            card.Bounds = new Rectangle(x, y, width, height);
            _cardsHost.Controls.Add(card);
        }
    }

    private Control CreateCard(LauncherItemRuntime item, bool selected, int width, int height)
    {
        var card = new Panel
        {
            Width = width,
            Height = height,
            BackColor = selected ? Color.FromArgb(248, 248, 248) : Color.FromArgb(228, 228, 228),
            BorderStyle = BorderStyle.FixedSingle,
            Padding = new Padding(12)
        };

        var icon = new PictureBox
        {
            Size = new Size(width - 24, 110),
            Location = new Point(12, 12),
            SizeMode = PictureBoxSizeMode.Zoom,
            BackColor = Color.FromArgb(36, 36, 36),
            Image = LoadItemIcon(item.IconPath)
        };
        card.Controls.Add(icon);

        card.Controls.Add(CreateCardLabel(item.Title, new Rectangle(12, 132, width - 24, 34), 16, FontStyle.Bold, Color.Black));
        card.Controls.Add(CreateCardLabel(item.CommandLine, new Rectangle(12, 170, width - 24, 52), 9, FontStyle.Regular, Color.DimGray));
        card.Controls.Add(CreateCardLabel(item.Note ?? string.Empty, new Rectangle(12, height - 64, width - 24, 44), 10, FontStyle.Regular, Color.Black));
        return card;
    }

    private static Label CreateCardLabel(string text, Rectangle bounds, float fontSize, FontStyle style, Color color) => new()
    {
        Bounds = bounds,
        Text = text,
        ForeColor = color,
        Font = new Font("Microsoft YaHei UI", fontSize, style),
        AutoEllipsis = true
    };

    private Rectangle BuildCardsHostBounds()
    {
        if (_contentPanel is null)
        {
            return Rectangle.Empty;
        }

        const int centerY = 1440;
        var localCenterY = (int)MathF.Round((centerY / 1920f) * _contentPanel.Height);
        var height = 380;
        var y = Math.Max(0, localCenterY - (height / 2));
        return new Rectangle(0, y, _contentPanel.Width, Math.Min(height, _contentPanel.Height - y));
    }

    private static bool MatchesKey(Keys actual, string? configured) =>
        !string.IsNullOrWhiteSpace(configured) &&
        string.Equals(actual.ToString(), configured.Trim(), StringComparison.OrdinalIgnoreCase);

    private static Image? LoadItemIcon(string? path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return null;
        }

        var expanded = Environment.ExpandEnvironmentVariables(path);
        return File.Exists(expanded) ? Image.FromFile(expanded) : null;
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

public sealed record LauncherNavigationOptions(string NavigateLeftKey, string NavigateRightKey, string ConfirmKey)
{
    public static LauncherNavigationOptions Default { get; } = new("Left", "Right", "Enter");
}
