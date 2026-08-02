import { useEffect, useMemo, useState } from 'react';
import {
  useAgentStatus,
  useExecuteRemoteShutdown,
  useRemoteShutdownStatus,
} from '../hooks/usePower';
import { useAgentStore } from '../stores/agentStore';

export function PowerPage() {
  const baseUrl = useAgentStore((s) => s.baseUrl);
  const statusQuery = useAgentStatus();
  const shutdownQuery = useRemoteShutdownStatus();
  const executeMutation = useExecuteRemoteShutdown();

  const [confirming, setConfirming] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const machineName = statusQuery.data?.machineName ?? '未知设备';
  const agentAddress = statusQuery.data?.baseUrl ?? baseUrl;
  const remoteShutdownEnabled = Boolean(
    statusQuery.data?.capabilities.remoteShutdown &&
      shutdownQuery.data?.available,
  );

  const busy = executeMutation.isPending;
  const mutationError = executeMutation.error;

  useEffect(() => {
    if (!mutationError) return;
    setErrorMessage(
      mutationError instanceof Error ? mutationError.message : '操作失败',
    );
  }, [mutationError]);

  const statusText = useMemo(() => {
    const shutdown = shutdownQuery.data;
    if (!shutdown) return '正在读取状态';
    if (shutdown.state === 'failed') return `失败：${shutdown.error ?? '未知错误'}`;
    if (shutdown.state === 'executing') return '正在关机';
    return remoteShutdownEnabled ? '可用' : '不可用';
  }, [remoteShutdownEnabled, shutdownQuery.data]);

  const execute = () => {
    setErrorMessage(null);
    executeMutation.mutate(undefined, {
      onSuccess: () => {
        setConfirming(false);
      },
    });
  };

  return (
    <section className="power-page">
      <header className="power-header">
        <div>
          <h1>电源</h1>
          <p className="power-subtitle">
            <span>{machineName}</span>
            <span className="power-agent-url">{agentAddress}</span>
          </p>
        </div>
        <span
          className={
            remoteShutdownEnabled
              ? 'power-status-pill is-on'
              : 'power-status-pill'
          }
        >
          {statusText}
        </span>
      </header>

      {errorMessage ? (
        <div role="alert" className="power-error">
          {errorMessage}
        </div>
      ) : null}

      <div className="power-card">
        <div className="power-card-header">
          <h2>远程关机</h2>
          <span className="power-card-value">
            {remoteShutdownEnabled ? '已启用' : '未启用'}
          </span>
        </div>
        <p className="power-copy">
          该操作会关闭当前连接的 Windows 电脑。请确认 Agent 已启用远程关机功能。
        </p>

        {!confirming ? (
          <button
            type="button"
            className="power-danger-button"
            disabled={!remoteShutdownEnabled || busy}
            onClick={() => setConfirming(true)}
          >
            远程关机
          </button>
        ) : null}

        {confirming ? (
          <div className="power-confirm">
            <p>
              确认关闭 <strong>{machineName}</strong>（{agentAddress}）？
              确认后将立即关机。
            </p>
            <div className="power-actions">
              <button
                type="button"
                className="power-secondary-button"
                onClick={() => setConfirming(false)}
                disabled={busy}
              >
                返回
              </button>
              <button
                type="button"
                className="power-danger-button"
                onClick={execute}
                disabled={busy}
              >
                确认关机
              </button>
            </div>
          </div>
        ) : null}
      </div>
    </section>
  );
}
