using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using MaimaiHomeAgent.Launcher;

namespace MaimaiHomeAgent.Ui.Avalonia.Launcher;

internal sealed class AvaloniaLauncherWindowHost : ILauncherWindowHost
{
    private readonly ILogger<AvaloniaLauncherWindowHost> _logger;
    private readonly IAvaloniaUiThread _uiThread;
    private LauncherWindowViewModel? _viewModel;
    private LauncherWindow? _window;

    public AvaloniaLauncherWindowHost(
        IAvaloniaUiThread uiThread,
        ILogger<AvaloniaLauncherWindowHost> logger)
    {
        _uiThread = uiThread;
        _logger = logger;
    }

    public bool IsVisible => _viewModel?.IsVisible ?? false;

    public Task ShowAsync(
        IReadOnlyList<LauncherItemRuntime> items,
        LauncherNavigationOptions navigation,
        Func<string, CancellationToken, Task> onKeySelected,
        CancellationToken ct = default)
    {
        return _uiThread.InvokeAsync(() =>
        {
            if (_window is null)
            {
                _viewModel = new LauncherWindowViewModel(items, navigation, onKeySelected, _logger);
                _window = new LauncherWindow(_viewModel);
                _window.Closed += (_, _) =>
                {
                    _window = null;
                    if (_viewModel is not null) _viewModel.IsVisible = false;
                };
            }
            else
            {
                _viewModel!.Update(items, navigation, onKeySelected);
            }

            _window.Show();
            _window.Activate();
            _window.WindowState = WindowState.Maximized;
            _viewModel!.IsVisible = true;
            return Task.CompletedTask;
        }, ct);
    }

    public Task MinimizeAsync(CancellationToken ct = default)
    {
        return _uiThread.InvokeAsync(() =>
        {
            if (_window is not null) _window.WindowState = WindowState.Minimized;

            if (_viewModel is not null) _viewModel.IsVisible = false;
            return Task.CompletedTask;
        }, ct);
    }

    public Task HideAsync(CancellationToken ct = default)
    {
        return _uiThread.InvokeAsync(() =>
        {
            _window?.Hide();
            if (_viewModel is not null) _viewModel.IsVisible = false;
            return Task.CompletedTask;
        }, ct);
    }
}

internal sealed class LauncherWindowViewModel
{
    private readonly ILogger _logger;
    private Func<string, CancellationToken, Task> _onKeySelected;

    public LauncherWindowViewModel(
        IReadOnlyList<LauncherItemRuntime> items,
        LauncherNavigationOptions navigation,
        Func<string, CancellationToken, Task> onKeySelected,
        ILogger logger)
    {
        Items = items;
        Navigation = navigation;
        _onKeySelected = onKeySelected;
        _logger = logger;
    }

    public bool IsVisible { get; set; }
    public int SelectedIndex { get; set; }
    public IReadOnlyList<LauncherItemRuntime> Items { get; private set; }

    public LauncherNavigationOptions Navigation { get; private set; }

    public void Update(IReadOnlyList<LauncherItemRuntime> items, LauncherNavigationOptions navigation,
        Func<string, CancellationToken, Task> onKeySelected)
    {
        Items = items;
        Navigation = navigation;
        _onKeySelected = onKeySelected;
        if (SelectedIndex >= Items.Count) SelectedIndex = Math.Max(0, Items.Count - 1);
    }

    public void MoveLeft()
    {
        if (Items.Count == 0) return;
        SelectedIndex = (SelectedIndex - 1 + Items.Count) % Items.Count;
    }

    public void MoveRight()
    {
        if (Items.Count == 0) return;
        SelectedIndex = (SelectedIndex + 1) % Items.Count;
    }

    public async Task ConfirmAsync()
    {
        if (Items.Count == 0) return;
        var item = Items[SelectedIndex];
        try
        {
            await _onKeySelected(item.Id, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Launcher confirm failed for {ItemId}.", item.Id);
        }
    }

    public bool MatchesKey(Key key, string? configuredKey)
    {
        if (string.IsNullOrWhiteSpace(configuredKey)) return false;
        return Enum.TryParse<Key>(configuredKey, true, out var parsed) && parsed == key;
    }
}

internal sealed class LauncherWindow : Window
{
    private const float PortraitAspectRatio = 9f / 16f;

    // Design canvas is 1080x1920. The visible game art is the lower 1080x1080 square,
    // i.e. y in [840, 1920]. Its center is y = 1380.
    private const double VisibleSquareCenterYRatio = 1380d / 1920d;
    private readonly LauncherWindowViewModel _vm;
    private Canvas? _cardsHost;
    private Panel? _contentArea;

    public LauncherWindow(LauncherWindowViewModel vm)
    {
        _vm = vm;
        Title = "Maimai Launcher";
        WindowState = WindowState.Maximized;
        SystemDecorations = SystemDecorations.None;
        Background = Brushes.Black;
        Topmost = true;
        Content = BuildContent();
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        base.OnKeyDown(e);

        if (e.Key == Key.Escape)
        {
            e.Handled = true;
            Hide();
            _vm.IsVisible = false;
            return;
        }

        if (_vm.MatchesKey(e.Key, _vm.Navigation.NavigateLeftKey))
        {
            _vm.MoveLeft();
            RenderCards();
            e.Handled = true;
            return;
        }

        if (_vm.MatchesKey(e.Key, _vm.Navigation.NavigateRightKey))
        {
            _vm.MoveRight();
            RenderCards();
            e.Handled = true;
            return;
        }

        if (_vm.MatchesKey(e.Key, _vm.Navigation.ConfirmKey))
        {
            _ = _vm.ConfirmAsync();
            e.Handled = true;
        }
    }

    private Control BuildContent()
    {
        var root = new Panel { Background = Brushes.Black };

        var contentArea = new Panel
        {
            Background = new SolidColorBrush(Color.FromRgb(24, 24, 24)),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            ClipToBounds = true
        };
        _contentArea = contentArea;

        // Load background reference image at 20% opacity
        var bgImage = LoadReferenceImage();
        if (bgImage is not null)
        {
            var bg = new Image
            {
                Source = bgImage,
                Stretch = Stretch.Uniform,
                Opacity = 0.2
            };
            contentArea.Children.Add(bg);
        }

        _cardsHost = new Canvas();
        contentArea.Children.Add(_cardsHost);

        // Bind content area size to maintain 9:16 ratio (height fills the screen)
        root.SizeChanged += (_, args) =>
        {
            var w = args.NewSize.Width;
            var h = args.NewSize.Height;
            var maxHeight = h;
            var contentWidth = maxHeight * PortraitAspectRatio;
            if (contentWidth > w)
            {
                contentWidth = w;
                maxHeight = contentWidth / PortraitAspectRatio;
            }

            contentArea.Width = contentWidth;
            contentArea.Height = maxHeight;
            RenderCards();
        };

        root.Children.Add(contentArea);
        return root;
    }

    private void RenderCards()
    {
        if (_cardsHost is null || _contentArea is null) return;
        _cardsHost.Children.Clear();

        if (_vm.Items.Count == 0) return;

        var hostWidth = _contentArea.Width;
        var hostHeight = _contentArea.Height;
        if (double.IsNaN(hostWidth) || double.IsNaN(hostHeight) || hostWidth <= 0 || hostHeight <= 0) return;

        var centerX = hostWidth / 2;
        // The visible game art area is the lower 1080x1080 square in the 1080x1920 design.
        // Center cards in that square: y = 840 + 1080 / 2 = 1380.
        var centerY = hostHeight * VisibleSquareCenterYRatio;

        // Card metrics scale with the design width (1080) so cards stay inside the
        // lower 1080x1080 square on any resolution.
        var designScale = hostWidth / 1080d;
        var cardWidth = 220 * designScale;
        var cardHeight = 300 * designScale;
        var cardGap = 24 * designScale;

        for (var i = 0; i < _vm.Items.Count; i++)
        {
            var relative = i - _vm.SelectedIndex;
            var scale = i == _vm.SelectedIndex ? 1.2 : 0.9;
            var w = cardWidth * scale;
            var h = cardHeight * scale;
            var x = centerX - w / 2 + relative * (cardWidth + cardGap);
            var y = centerY - h / 2;

            var card = BuildCard(_vm.Items[i], i == _vm.SelectedIndex, w, h);
            Canvas.SetLeft(card, x);
            Canvas.SetTop(card, y);
            _cardsHost.Children.Add(card);
        }
    }

    private static Control BuildCard(LauncherItemRuntime item, bool selected, double w, double h)
    {
        var border = new Border
        {
            Width = w,
            Height = h,
            Background = selected ? Brushes.White : new SolidColorBrush(Color.FromRgb(228, 228, 228)),
            BorderBrush = selected ? new SolidColorBrush(Color.FromRgb(0, 120, 215)) : Brushes.Gray,
            BorderThickness = new Thickness(selected ? 3 : 1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(12),
            Child = new StackPanel
            {
                Spacing = 8,
                Children =
                {
                    new Border
                    {
                        Height = 80,
                        Background = new SolidColorBrush(Color.FromRgb(36, 36, 36)),
                        CornerRadius = new CornerRadius(4),
                        Child = LoadItemIcon(item.IconPath)
                    },
                    new TextBlock
                    {
                        Text = item.Name,
                        FontWeight = FontWeight.Bold,
                        FontSize = 14,
                        Foreground = Brushes.Black,
                        TextTrimming = TextTrimming.CharacterEllipsis
                    },
                    new TextBlock
                    {
                        Text = item.CommandLine,
                        FontSize = 11,
                        Foreground = Brushes.DimGray,
                        TextTrimming = TextTrimming.CharacterEllipsis
                    },
                    new TextBlock
                    {
                        Text = item.Note ?? "",
                        FontSize = 11,
                        Foreground = Brushes.Gray,
                        TextTrimming = TextTrimming.CharacterEllipsis
                    }
                }
            }
        };
        return border;
    }

    private static Control? LoadItemIcon(string? iconPath)
    {
        if (string.IsNullOrWhiteSpace(iconPath)) return null;
        var expanded = Environment.ExpandEnvironmentVariables(iconPath);
        if (!File.Exists(expanded)) return null;
        try
        {
            var bitmap = new Bitmap(expanded);
            return new Image { Source = bitmap, Stretch = Stretch.Uniform };
        }
        catch
        {
            return null;
        }
    }

    private static Bitmap? LoadReferenceImage()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "Resources", "GameScreenshot.jpg");
        if (!File.Exists(path)) return null;
        try
        {
            return new Bitmap(path);
        }
        catch
        {
            return null;
        }
    }
}