using System.Text.Json;
using System.Windows.Forms;
using MaimaiHomeAgent.Ui;
using Microsoft.Extensions.Logging;

namespace MaimaiHomeAgent.Settings;

internal sealed class WinFormsSettingsWindowHost : ISettingsWindowHost
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly IAgentSettingsService _settings;
    private readonly IWinFormsUiThread _uiThread;
    private readonly ILogger<WinFormsSettingsWindowHost> _logger;
    private Form? _form;
    private TextBox? _editor;

    public WinFormsSettingsWindowHost(
        IAgentSettingsService settings,
        IWinFormsUiThread uiThread,
        ILogger<WinFormsSettingsWindowHost> logger)
    {
        _settings = settings;
        _uiThread = uiThread;
        _logger = logger;
    }

    public Task ShowAsync(CancellationToken ct = default) => _uiThread.InvokeAsync(async () =>
    {
        if (_form is { IsDisposed: false })
        {
            _form.Show();
            _form.WindowState = FormWindowState.Normal;
            _form.Activate();
            return;
        }

        var snapshot = await _settings.GetAsync(ct).ConfigureAwait(true);
        _form = CreateForm(ToUpdateRequest(snapshot));
        _form.FormClosed += (_, _) =>
        {
            _form?.Dispose();
            _form = null;
            _editor = null;
        };
        _form.Show();
    }, ct);

    private Form CreateForm(AgentSettingsUpdateRequest request)
    {
        var form = new Form
        {
            Text = "Maimai Home Agent 设置",
            Width = 960,
            Height = 720,
            StartPosition = FormStartPosition.CenterScreen,
            MinimizeBox = true,
            MaximizeBox = true
        };

        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 3,
            Padding = new Padding(12)
        };
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));

        var description = new Label
        {
            AutoSize = true,
            Text = "编辑 JSON 后点击保存。adminPassword 为空或 null 表示不修改管理员密码。",
            Dock = DockStyle.Top
        };

        _editor = new TextBox
        {
            Multiline = true,
            ScrollBars = ScrollBars.Both,
            WordWrap = false,
            AcceptsReturn = true,
            AcceptsTab = true,
            Dock = DockStyle.Fill,
            Font = new System.Drawing.Font("Consolas", 10),
            Text = JsonSerializer.Serialize(request, JsonOptions)
        };

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            AutoSize = true
        };

        var save = new Button { Text = "保存", AutoSize = true };
        save.Click += async (_, _) => await SaveAsync();

        var reload = new Button { Text = "重新加载", AutoSize = true };
        reload.Click += async (_, _) => await ReloadAsync();

        var close = new Button { Text = "关闭", AutoSize = true };
        close.Click += (_, _) => form.Close();

        buttons.Controls.Add(save);
        buttons.Controls.Add(reload);
        buttons.Controls.Add(close);

        panel.Controls.Add(description, 0, 0);
        panel.Controls.Add(_editor, 0, 1);
        panel.Controls.Add(buttons, 0, 2);
        form.Controls.Add(panel);

        return form;
    }

    private async Task ReloadAsync()
    {
        if (_editor is null)
        {
            return;
        }

        try
        {
            var snapshot = await _settings.GetAsync().ConfigureAwait(true);
            _editor.Text = JsonSerializer.Serialize(ToUpdateRequest(snapshot), JsonOptions);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to reload settings window.");
            MessageBox.Show("重新加载设置失败。", "Maimai Home Agent", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private async Task SaveAsync()
    {
        if (_editor is null)
        {
            return;
        }

        AgentSettingsUpdateRequest? request;
        try
        {
            request = JsonSerializer.Deserialize<AgentSettingsUpdateRequest>(_editor.Text, JsonOptions);
        }
        catch (JsonException ex)
        {
            MessageBox.Show($"JSON 格式错误：{ex.Message}", "Maimai Home Agent", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        if (request is null)
        {
            MessageBox.Show("设置内容不能为空。", "Maimai Home Agent", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        try
        {
            var result = await _settings.UpdateAsync(request).ConfigureAwait(true);
            if (!result.Success)
            {
                var message = string.Join(Environment.NewLine, result.Errors.Select(error => $"- {error.Message}"));
                MessageBox.Show(message, "保存失败", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }

            _editor.Text = JsonSerializer.Serialize(ToUpdateRequest(result.Settings!), JsonOptions);
            MessageBox.Show("设置已保存。", "Maimai Home Agent", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to save settings from settings window.");
            MessageBox.Show("保存设置失败。", "Maimai Home Agent", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }

    private static AgentSettingsUpdateRequest ToUpdateRequest(AgentSettingsSnapshot snapshot) => new(
        AdminPassword: null,
        AutoStartEnabled: snapshot.AutoStartEnabled,
        Launcher: snapshot.Launcher,
        FileRoots: snapshot.FileRoots,
        RemoteShutdown: snapshot.RemoteShutdown);
}
