import { useState } from 'react';
import { useSettings, useUpdateSettings } from '../hooks/useSettings';

export function SettingsPage() {
  const settingsQuery = useSettings();
  const updateMutation = useUpdateSettings();
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const settings = settingsQuery.data;
  const busy = updateMutation.isPending;

  const handleSave = () => {
    if (!settings) return;
    setErrorMessage(null);
    setSuccessMessage(null);
    updateMutation.mutate(
      {
        autoStartEnabled: settings.autoStartEnabled,
        launcher: settings.launcher,
        fileRoots: settings.fileRoots,
        remoteShutdown: settings.remoteShutdown,
      },
      {
        onSuccess: () => setSuccessMessage('设置已保存'),
        onError: (e) => setErrorMessage(e instanceof Error ? e.message : '保存失败'),
      },
    );
  };

  return (
    <section className="settings-page">
      <header>
        <h1>设置</h1>
        <button type="button" onClick={handleSave} disabled={!settings || busy}>
          {busy ? '保存中…' : '保存设置'}
        </button>
      </header>

      {errorMessage && (
        <div role="alert" className="settings-error">
          {errorMessage}
        </div>
      )}
      {successMessage && (
        <div role="status" className="settings-success">
          {successMessage}
        </div>
      )}

      {settingsQuery.isLoading && <p className="settings-muted">加载中…</p>}
      {settingsQuery.isError && (
        <p className="settings-error">加载失败: {settingsQuery.error?.message}</p>
      )}

      {settings && (
        <>
          <div className="settings-card">
            <h2>基本设置</h2>
            <label>
              开机自启
              <input
                type="checkbox"
                checked={settings.autoStartEnabled}
                onChange={() => {
                  settingsQuery.refetch();
                }}
              />
            </label>
            <label>
              启动器自动显示
              <input
                type="checkbox"
                checked={settings.launcher.showOnAgentStart}
                onChange={() => {
                  settingsQuery.refetch();
                }}
              />
            </label>
          </div>

          <div className="settings-card">
            <h2>远程关机</h2>
            <label>
              启用远程关机
              <input
                type="checkbox"
                checked={settings.remoteShutdown.enabled}
                onChange={() => {
                  settingsQuery.refetch();
                }}
              />
            </label>
          </div>

          <div className="settings-card">
            <h2>文件根目录</h2>
            {settings.fileRoots.length === 0 ? (
              <p className="settings-muted">未配置文件根目录。</p>
            ) : (
              <ul className="settings-root-list">
                {settings.fileRoots.map((root) => (
                  <li key={root.id}>
                    <strong>{root.name}</strong>
                    {root.readOnly && <span className="settings-badge">只读</span>}
                    <span className="settings-path">{root.path}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="settings-card">
            <h2>启动项</h2>
            {settings.launcher.items.length === 0 ? (
              <p className="settings-muted">未配置启动项。</p>
            ) : (
              <ul className="settings-item-list">
                {settings.launcher.items.map((item) => (
                  <li key={item.id}>
                    <strong>{item.name || item.title || '(未命名)'}</strong>
                    <span className={item.enabled ? 'settings-badge is-on' : 'settings-badge'}>
                      {item.enabled ? '已启用' : '已禁用'}
                    </span>
                    {item.commandLine && (
                      <code className="settings-command">{item.commandLine}</code>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </section>
  );
}
