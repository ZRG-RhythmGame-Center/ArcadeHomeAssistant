import { useState } from 'react';
import {
  useLauncherStatus,
  useShowLauncher,
  useStartLauncherItem,
  useStopLauncherItem,
} from '../hooks/useLauncher';

export function LauncherPage() {
  const statusQuery = useLauncherStatus();
  const showMutation = useShowLauncher();
  const startMutation = useStartLauncherItem();
  const stopMutation = useStopLauncherItem();
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const status = statusQuery.data;
  const busy = showMutation.isPending || startMutation.isPending || stopMutation.isPending;

  const handleShow = () => {
    setErrorMessage(null);
    showMutation.mutate(undefined, {
      onError: (e) => setErrorMessage(e instanceof Error ? e.message : '操作失败'),
    });
  };

  const handleStop = () => {
    setErrorMessage(null);
    stopMutation.mutate(undefined, {
      onError: (e) => setErrorMessage(e instanceof Error ? e.message : '操作失败'),
    });
  };

  return (
    <section className="launcher-page">
      <header>
        <h1>启动器</h1>
        {status && (
          <span className={`launcher-status-pill ${status.state === 'running' ? 'is-on' : ''}`}>
            {status.state === 'running' ? `运行中: ${status.activeItemName ?? ''}` : status.state}
          </span>
        )}
      </header>

      {errorMessage && (
        <div role="alert" className="launcher-error">
          {errorMessage}
        </div>
      )}

      {statusQuery.isLoading && <p className="launcher-muted">加载中…</p>}
      {statusQuery.isError && (
        <p className="launcher-error">加载失败: {statusQuery.error?.message}</p>
      )}

      {status && (
        <div className="launcher-card">
          <div className="launcher-card-header">
            <h2>启动器状态</h2>
          </div>
          <p>窗口: {status.visible ? '已显示' : '已隐藏'}</p>
          <p>状态: {status.state}</p>
          {status.lastError && (
            <p className="launcher-error">错误: {status.lastError}</p>
          )}

          <div className="launcher-actions">
            <button type="button" onClick={handleShow} disabled={busy}>
              显示启动器
            </button>
            {status.hasActiveItem && (
              <button
                type="button"
                onClick={handleStop}
                disabled={busy}
                className="launcher-danger-button"
              >
                停止当前项
              </button>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
