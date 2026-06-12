using Avalonia;
using Avalonia.Controls;
using Avalonia.Input;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Threading;
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
        Func<string, CancellationToken, Task<LauncherActionResult>> onKeySelected,
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
            _window.WindowState = WindowState.FullScreen;
            _window.FocusForKeyboardInput();
            _viewModel!.IsVisible = true;
            return Task.CompletedTask;
        }, ct);
    }

    public Task MinimizeAsync(CancellationToken ct = default)
    {
        return _uiThread.InvokeAsync(() =>
        {
            _window?.Hide();

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
    private Func<string, CancellationToken, Task<LauncherActionResult>> _onKeySelected;

    public LauncherWindowViewModel(
        IReadOnlyList<LauncherItemRuntime> items,
        LauncherNavigationOptions navigation,
        Func<string, CancellationToken, Task<LauncherActionResult>> onKeySelected,
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
    public string? StatusMessage { get; private set; }

    public LauncherNavigationOptions Navigation { get; private set; }

    public void Update(IReadOnlyList<LauncherItemRuntime> items, LauncherNavigationOptions navigation,
        Func<string, CancellationToken, Task<LauncherActionResult>> onKeySelected)
    {
        Items = items;
        Navigation = navigation;
        _onKeySelected = onKeySelected;
        if (SelectedIndex >= Items.Count) SelectedIndex = Math.Max(0, Items.Count - 1);
    }

    public void MoveLeft()
    {
        if (Items.Count == 0) return;
        StatusMessage = null;
        SelectedIndex = (SelectedIndex - 1 + Items.Count) % Items.Count;
    }

    public void MoveRight()
    {
        if (Items.Count == 0) return;
        StatusMessage = null;
        SelectedIndex = (SelectedIndex + 1) % Items.Count;
    }

    public async Task ConfirmAsync()
    {
        if (Items.Count == 0) return;
        var item = Items[SelectedIndex];
        try
        {
            var result = await _onKeySelected(item.Id, CancellationToken.None).ConfigureAwait(false);
            StatusMessage = result.Accepted
                ? null
                : result.Message ?? "无法启动当前启动项。";
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Launcher confirm failed for {ItemId}.", item.Id);
            StatusMessage = "启动失败，请查看日志。";
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
        WindowState = WindowState.FullScreen;
        SystemDecorations = SystemDecorations.None;
        Background = Brushes.Black;
        Focusable = true;
        Topmost = true;
        Content = BuildContent();
    }

    public void FocusForKeyboardInput()
    {
        Activate();
        Focus();

        // Some Windows focus changes settle after Show/FullScreen completes. Queue a second
        // focus request so launcher navigation keys work immediately after the window appears.
        Dispatcher.UIThread.Post(() =>
        {
            Topmost = false;
            Topmost = true;
            Activate();
            Focus();
        }, DispatcherPriority.Background);
    }

    protected override async void OnKeyDown(KeyEventArgs e)
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
            await _vm.ConfirmAsync().ConfigureAwait(true);
            RenderCards();
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

        // Load background: user wallpaper at full opacity, fallback to built-in reference at 20%
        var (bgImage, bgOpacity) = LoadBackgroundImage(_vm.Navigation.BackgroundImagePath);
        if (bgImage is not null)
        {
            var bg = new Image
            {
                Source = bgImage,
                Stretch = Stretch.Uniform,
                Opacity = bgOpacity
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

        if (!string.IsNullOrWhiteSpace(_vm.StatusMessage))
        {
            var promptWidth = cardWidth * 1.2;
            var promptHeight = cardHeight * 1.2;
            var prompt = BuildPromptCard(_vm.StatusMessage, _vm.Navigation.StopKey, promptWidth, promptHeight);
            Canvas.SetLeft(prompt, centerX - promptWidth / 2);
            Canvas.SetTop(prompt, centerY - promptHeight / 2);
            _cardsHost.Children.Add(prompt);
            return;
        }

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

    private static Control BuildPromptCard(string message, string stopKey, double w, double h)
    {
        return new Border
        {
            Width = w,
            Height = h,
            Background = new SolidColorBrush(Color.FromRgb(255, 248, 220)),
            BorderBrush = new SolidColorBrush(Color.FromRgb(255, 174, 0)),
            BorderThickness = new Thickness(3),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(16),
            Child = new StackPanel
            {
                VerticalAlignment = VerticalAlignment.Center,
                Spacing = 12,
                Children =
                {
                    new TextBlock
                    {
                        Text = "无法启动",
                        FontWeight = FontWeight.Bold,
                        FontSize = 18,
                        Foreground = Brushes.Black,
                        TextAlignment = TextAlignment.Center
                    },
                    new TextBlock
                    {
                        Text = message,
                        FontSize = 13,
                        Foreground = Brushes.Black,
                        TextWrapping = TextWrapping.Wrap,
                        TextAlignment = TextAlignment.Center
                    },
                    new TextBlock
                    {
                        Text = $"请先按 {stopKey} 关闭当前启动项",
                        FontSize = 12,
                        Foreground = Brushes.DimGray,
                        TextWrapping = TextWrapping.Wrap,
                        TextAlignment = TextAlignment.Center
                    }
                }
            }
        };
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

    private static (Bitmap? Image, double Opacity) LoadBackgroundImage(string? userPath)
    {
        // Try user-configured wallpaper first (full opacity)
        if (!string.IsNullOrWhiteSpace(userPath))
        {
            var expanded = Environment.ExpandEnvironmentVariables(userPath);
            if (File.Exists(expanded))
            {
                try
                {
                    return (new Bitmap(expanded), 1.0);
                }
                catch
                {
                    /* fall through to built-in */
                }
            }
        }

        // Fallback to built-in reference image at 20% opacity
        var path = Path.Combine(AppContext.BaseDirectory, "Resources", "GameScreenshot.jpg");
        if (!File.Exists(path)) return (null, 0);
        try
        {
            return (new Bitmap(path), 0.2);
        }
        catch
        {
            return (null, 0);
        }
    }
}
