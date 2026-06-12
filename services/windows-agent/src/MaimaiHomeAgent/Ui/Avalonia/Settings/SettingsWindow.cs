using Avalonia;
using Avalonia.Controls;
using Avalonia.Data;
using Avalonia.Layout;
using Avalonia.Media;

namespace MaimaiHomeAgent.Ui.Avalonia.Settings;

internal sealed class SettingsWindow : Window
{
    public SettingsWindow()
    {
        Title = "Maimai Home Agent 设置";
        Width = 1100;
        Height = 780;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Content = BuildContent();
    }

    private Control BuildContent()
    {
        var root = new Grid
        {
            RowDefinitions = RowDefinitions.Parse("*,Auto"),
            ColumnDefinitions = ColumnDefinitions.Parse("200,*")
        };

        var categories = new ListBox
        {
            ItemsSource = new[] { "管理员", "启动器", "启动项", "文件根目录", "远程关机" },
            SelectedIndex = 0
        };
        categories.SelectionChanged += (_, _) =>
        {
            if (DataContext is SettingsWindowViewModel vm) vm.SelectedCategoryIndex = categories.SelectedIndex;
        };
        Grid.SetRowSpan(categories, 2);
        root.Children.Add(categories);

        var contentArea = new ContentControl();
        contentArea.DataContext = DataContext;
        Grid.SetColumn(contentArea, 1);
        root.Children.Add(contentArea);

        DataContextChanged += (_, _) =>
        {
            if (DataContext is SettingsWindowViewModel vm)
            {
                vm.PropertyChanged += (_, args) =>
                {
                    if (args.PropertyName == nameof(SettingsWindowViewModel.SelectedCategoryIndex))
                    {
                        contentArea.Content = BuildCategoryPage(vm.SelectedCategoryIndex, vm);
                        categories.SelectedIndex = vm.SelectedCategoryIndex;
                    }
                };
                contentArea.Content = BuildCategoryPage(vm.SelectedCategoryIndex, vm);
            }
        };

        var buttons = BuildButtons();
        Grid.SetColumn(buttons, 1);
        Grid.SetRow(buttons, 1);
        root.Children.Add(buttons);

        return root;
    }

    private Control BuildCategoryPage(int index, SettingsWindowViewModel vm)
    {
        return index switch
        {
            0 => BuildAdminPage(vm),
            1 => BuildLauncherPage(vm),
            2 => BuildLauncherItemsPage(vm),
            3 => BuildFileRootsPage(vm),
            4 => BuildRemoteShutdownPage(vm),
            _ => new TextBlock { Text = "未知分类" }
        };
    }

    private static Control BuildAdminPage(SettingsWindowViewModel vm)
    {
        var panel = new StackPanel { Spacing = 12, Margin = new Thickness(16) };
        panel.Children.Add(new TextBlock { Text = "管理员", FontSize = 18, FontWeight = FontWeight.Bold });
        panel.Children.Add(LabeledTextBox("管理员密码", vm.AdminPassword ?? "", text => vm.AdminPassword = text, true));
        panel.Children.Add(new TextBlock
        {
            Text = "留空表示不修改当前管理员密码。",
            Foreground = Brushes.Gray,
            FontSize = 12
        });
        return panel;
    }

    private static Control BuildLauncherPage(SettingsWindowViewModel vm)
    {
        var panel = new StackPanel { Spacing = 12, Margin = new Thickness(16) };
        panel.Children.Add(new TextBlock { Text = "启动器", FontSize = 18, FontWeight = FontWeight.Bold });
        panel.Children.Add(LabeledCheckBox("Windows 开机自启", vm.AutoStartEnabled, v => vm.AutoStartEnabled = v));
        panel.Children.Add(LabeledCheckBox("Agent 启动后自动显示启动器", vm.LauncherShowOnStart,
            v => vm.LauncherShowOnStart = v));
        panel.Children.Add(LabeledNumberBox("画布宽度", vm.CanvasWidth, v => vm.CanvasWidth = v));
        panel.Children.Add(LabeledNumberBox("画布高度", vm.CanvasHeight, v => vm.CanvasHeight = v));
        panel.Children.Add(LabeledTextBox("左移按键", vm.NavigateLeftKey, v => vm.NavigateLeftKey = v));
        panel.Children.Add(LabeledTextBox("右移按键", vm.NavigateRightKey, v => vm.NavigateRightKey = v));
        panel.Children.Add(LabeledTextBox("确认按键", vm.ConfirmKey, v => vm.ConfirmKey = v));
        return panel;
    }

    private static Control BuildLauncherItemsPage(SettingsWindowViewModel vm)
    {
        var panel = new Grid
        {
            ColumnDefinitions = ColumnDefinitions.Parse("240,*"),
            RowDefinitions = RowDefinitions.Parse("*,Auto"),
            Margin = new Thickness(16)
        };

        var list = new ListBox
        {
            ItemsSource = vm.LauncherItems,
            DisplayMemberBinding = new Binding("Name")
        };

        var detail = new ScrollViewer();
        Grid.SetColumn(detail, 1);

        list.SelectionChanged += (_, _) =>
        {
            if (list.SelectedItem is LauncherItemViewModel item) detail.Content = BuildLauncherItemDetail(item);
        };

        panel.Children.Add(list);
        panel.Children.Add(detail);

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        Grid.SetRow(buttons, 1);
        var addBtn = new Button { Content = "新增" };
        addBtn.Click += (_, _) =>
        {
            vm.AddLauncherItem();
            list.SelectedIndex = vm.LauncherItems.Count - 1;
        };
        var removeBtn = new Button { Content = "删除" };
        removeBtn.Click += (_, _) =>
        {
            if (list.SelectedItem is LauncherItemViewModel item) vm.RemoveLauncherItem(item);
        };
        buttons.Children.Add(addBtn);
        buttons.Children.Add(removeBtn);
        panel.Children.Add(buttons);

        return panel;
    }

    private static Control BuildLauncherItemDetail(LauncherItemViewModel item)
    {
        var panel = new StackPanel { Spacing = 8, Margin = new Thickness(12, 0, 0, 0) };
        panel.Children.Add(LabeledTextBox("ID", item.Id ?? "", v => item.Id = v));
        panel.Children.Add(LabeledTextBox("名称", item.Name ?? "", v => item.Name = v));
        panel.Children.Add(LabeledTextBox("备注", item.Note ?? "", v => item.Note = v));
        panel.Children.Add(LabeledTextBox("图标路径", item.IconPath ?? "", v => item.IconPath = v));
        panel.Children.Add(LabeledTextBox("启动命令", item.CommandLine ?? "", v => item.CommandLine = v));
        panel.Children.Add(LabeledTextBox("启动工作目录", item.WorkingDirectory ?? "", v => item.WorkingDirectory = v));
        panel.Children.Add(LabeledTextBox("关闭命令", item.StopCommandLine ?? "", v => item.StopCommandLine = v));
        panel.Children.Add(
            LabeledTextBox("关闭工作目录", item.StopWorkingDirectory ?? "", v => item.StopWorkingDirectory = v));
        panel.Children.Add(LabeledNumberBox("排序", item.OrderIndex, v => item.OrderIndex = v));
        panel.Children.Add(LabeledCheckBox("启用", item.Enabled, v => item.Enabled = v));
        return panel;
    }

    private static Control BuildFileRootsPage(SettingsWindowViewModel vm)
    {
        var panel = new Grid
        {
            ColumnDefinitions = ColumnDefinitions.Parse("240,*"),
            RowDefinitions = RowDefinitions.Parse("*,Auto"),
            Margin = new Thickness(16)
        };

        var list = new ListBox
        {
            ItemsSource = vm.FileRoots,
            DisplayMemberBinding = new Binding("Name")
        };

        var detail = new ScrollViewer();
        Grid.SetColumn(detail, 1);

        list.SelectionChanged += (_, _) =>
        {
            if (list.SelectedItem is FileRootViewModel item) detail.Content = BuildFileRootDetail(item);
        };

        panel.Children.Add(list);
        panel.Children.Add(detail);

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        Grid.SetRow(buttons, 1);
        var addBtn = new Button { Content = "新增" };
        addBtn.Click += (_, _) =>
        {
            vm.AddFileRoot();
            list.SelectedIndex = vm.FileRoots.Count - 1;
        };
        var removeBtn = new Button { Content = "删除" };
        removeBtn.Click += (_, _) =>
        {
            if (list.SelectedItem is FileRootViewModel item) vm.RemoveFileRoot(item);
        };
        buttons.Children.Add(addBtn);
        buttons.Children.Add(removeBtn);
        panel.Children.Add(buttons);

        return panel;
    }

    private static Control BuildFileRootDetail(FileRootViewModel item)
    {
        var panel = new StackPanel { Spacing = 8, Margin = new Thickness(12, 0, 0, 0) };
        panel.Children.Add(LabeledTextBox("ID", item.Id ?? "", v => item.Id = v));
        panel.Children.Add(LabeledTextBox("名称", item.Name ?? "", v => item.Name = v));
        panel.Children.Add(LabeledTextBox("路径", item.Path ?? "", v => item.Path = v));
        panel.Children.Add(LabeledCheckBox("只读", item.ReadOnly, v => item.ReadOnly = v));
        return panel;
    }

    private static Control BuildRemoteShutdownPage(SettingsWindowViewModel vm)
    {
        var panel = new StackPanel { Spacing = 12, Margin = new Thickness(16) };
        panel.Children.Add(new TextBlock { Text = "远程关机", FontSize = 18, FontWeight = FontWeight.Bold });
        panel.Children.Add(LabeledCheckBox("启用远程关机", vm.RemoteShutdownEnabled, v => vm.RemoteShutdownEnabled = v));
        panel.Children.Add(LabeledTextBox("控制令牌", vm.RemoteShutdownToken ?? "", v => vm.RemoteShutdownToken = v));
        return panel;
    }

    private Control BuildButtons()
    {
        var panel = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            Spacing = 8,
            Margin = new Thickness(16, 8)
        };

        var status = new TextBlock
        {
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 16, 0)
        };
        DataContextChanged += (_, _) =>
        {
            if (DataContext is SettingsWindowViewModel vm)
                vm.PropertyChanged += (_, args) =>
                {
                    if (args.PropertyName == nameof(SettingsWindowViewModel.StatusMessage))
                        status.Text = vm.StatusMessage;
                };
        };

        var saveBtn = new Button { Content = "保存" };
        saveBtn.Click += async (_, _) =>
        {
            if (DataContext is SettingsWindowViewModel vm) await vm.SaveAsync();
        };

        var reloadBtn = new Button { Content = "重新加载" };
        reloadBtn.Click += async (_, _) =>
        {
            if (DataContext is SettingsWindowViewModel vm) await vm.LoadAsync();
        };

        var closeBtn = new Button { Content = "关闭" };
        closeBtn.Click += (_, _) => Close();

        panel.Children.Add(status);
        panel.Children.Add(closeBtn);
        panel.Children.Add(reloadBtn);
        panel.Children.Add(saveBtn);
        return panel;
    }

    private static Control LabeledTextBox(string label, string value, Action<string> onChanged,
        bool revealPassword = false)
    {
        var row = new StackPanel { Spacing = 4 };
        row.Children.Add(new TextBlock { Text = label, FontSize = 12, Foreground = Brushes.Gray });
        var tb = new TextBox { Text = value, PasswordChar = revealPassword ? '*' : default };
        tb.TextChanged += (_, _) => onChanged(tb.Text ?? "");
        row.Children.Add(tb);
        return row;
    }

    private static Control LabeledNumberBox(string label, int value, Action<int> onChanged)
    {
        var row = new StackPanel { Spacing = 4 };
        row.Children.Add(new TextBlock { Text = label, FontSize = 12, Foreground = Brushes.Gray });
        var tb = new TextBox { Text = value.ToString() };
        tb.TextChanged += (_, _) =>
        {
            if (int.TryParse(tb.Text, out var num)) onChanged(num);
        };
        row.Children.Add(tb);
        return row;
    }

    private static Control LabeledCheckBox(string label, bool value, Action<bool> onChanged)
    {
        var cb = new CheckBox { Content = label, IsChecked = value };
        cb.IsCheckedChanged += (_, _) => onChanged(cb.IsChecked == true);
        return cb;
    }
}