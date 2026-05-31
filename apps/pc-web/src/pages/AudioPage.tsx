import { useEffect, useState } from 'react';
import {
  useAudioDevices,
  useAudioState,
  useSetMute,
  useSetVolume,
  useSwitchDevice,
} from '../hooks/useAudio';
import { useAgentStore } from '../stores/agentStore';

/**
 * PC web audio control page.
 *
 * Three controls, one connection-status header:
 * - master volume slider — commits ONLY on mouseUp / touchEnd / keyUp so the
 *   user can drag freely without spamming the agent with a POST per pixel.
 * - mute toggle — single click, single mutation.
 * - device list — flat list, click switches the default playback device.
 *
 * State comes from `useAudioState` / `useAudioDevices`; mutations are funneled
 * through `useSet*` hooks which invalidate the cache on success. Errors from
 * any mutation surface in a single shared toast region (role="alert").
 */
export function AudioPage() {
  const baseUrl = useAgentStore((s) => s.baseUrl);

  const stateQuery = useAudioState();
  const devicesQuery = useAudioDevices();

  const setVolumeMutation = useSetVolume();
  const setMuteMutation = useSetMute();
  const switchDeviceMutation = useSwitchDevice();

  // The slider is a controlled input that mirrors the cached server state
  // until the user grabs it; while dragging we want it to follow the mouse,
  // not snap back to the cached value on every re-render. `dragValue` carries
  // the in-progress drag; null means "follow the server".
  const [dragValue, setDragValue] = useState<number | null>(null);

  // Surface ANY mutation error in a single alert region — first error wins,
  // user can dismiss by triggering another action.
  const mutationError =
    setVolumeMutation.error ?? setMuteMutation.error ?? switchDeviceMutation.error;
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  useEffect(() => {
    if (mutationError) {
      setErrorMessage(
        mutationError instanceof Error ? mutationError.message : '操作失败'
      );
    }
  }, [mutationError]);

  // Connection status: any success on either query proves the agent is
  // reachable. We don't pretend "connected" until the first response lands.
  const connected = stateQuery.isSuccess || devicesQuery.isSuccess;

  // Slider is rendered in the 0..100 percentage space so the visual matches
  // user expectations; we scale to [0, 1] only when committing to the agent.
  const sliderValue =
    dragValue ??
    (stateQuery.data ? Math.round(stateQuery.data.masterVolume * 100) : 0);

  const commitVolume = () => {
    if (dragValue === null) return;
    const level = Math.max(0, Math.min(1, dragValue / 100));
    setVolumeMutation.mutate(level, {
      onSuccess: () => setDragValue(null),
      // On failure, keep the dragValue visible so the user sees their attempt
      // until they retry; clear it on success so the cache value takes over.
    });
  };

  const handleMuteToggle = () => {
    if (!stateQuery.data) return;
    setMuteMutation.mutate(!stateQuery.data.muted);
  };

  return (
    <section className="audio-page">
      <header className="audio-header">
        <div>
          <h1>音频</h1>
          <p className="audio-subtitle">
            <span
              className={
                connected ? 'audio-status-dot is-on' : 'audio-status-dot'
              }
              aria-hidden="true"
            />
            <span className="audio-agent-url">{baseUrl}</span>
            <span className="audio-status-label">
              {connected ? '已连接' : '连接中…'}
            </span>
          </p>
        </div>
      </header>

      {errorMessage ? (
        <div role="alert" className="audio-error">
          {errorMessage}
        </div>
      ) : null}

      <div className="audio-card">
        <div className="audio-card-header">
          <h2>主音量</h2>
          <span className="audio-card-value">{sliderValue}%</span>
        </div>
        <input
          type="range"
          min="0"
          max="100"
          step="1"
          value={sliderValue}
          aria-label="主音量"
          disabled={!stateQuery.data || setVolumeMutation.isPending}
          onChange={(e) => setDragValue(Number(e.target.value))}
          onMouseUp={commitVolume}
          onTouchEnd={commitVolume}
          onKeyUp={commitVolume}
          className="audio-slider"
        />
      </div>

      <div className="audio-card">
        <div className="audio-card-header">
          <h2>静音</h2>
          <span className="audio-card-value">
            {stateQuery.data?.muted ? '已静音' : '未静音'}
          </span>
        </div>
        <button
          type="button"
          className={
            stateQuery.data?.muted
              ? 'audio-mute-button is-on'
              : 'audio-mute-button'
          }
          onClick={handleMuteToggle}
          disabled={!stateQuery.data || setMuteMutation.isPending}
          aria-pressed={Boolean(stateQuery.data?.muted)}
        >
          {stateQuery.data?.muted ? '取消静音' : '静音'}
        </button>
      </div>

      <div className="audio-card">
        <div className="audio-card-header">
          <h2>输出设备</h2>
          <span className="audio-card-value">
            {devicesQuery.data?.length ?? 0} 个
          </span>
        </div>
        {devicesQuery.isLoading ? (
          <p className="audio-empty">加载中…</p>
        ) : devicesQuery.data && devicesQuery.data.length > 0 ? (
          <ul className="audio-device-list">
            {devicesQuery.data.map((device) => {
              const isDefault =
                device.isDefault ||
                stateQuery.data?.defaultDeviceId === device.id;
              return (
                <li key={device.id}>
                  <button
                    type="button"
                    className={
                      isDefault
                        ? 'audio-device-row is-default'
                        : 'audio-device-row'
                    }
                    onClick={() => {
                      if (isDefault) return;
                      switchDeviceMutation.mutate(device.id);
                    }}
                    disabled={
                      switchDeviceMutation.isPending && !isDefault
                    }
                    aria-current={isDefault ? 'true' : undefined}
                  >
                    <span className="audio-device-name">{device.name}</span>
                    <span className="audio-device-meta">
                      {isDefault ? '默认' : device.state}
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        ) : (
          <p className="audio-empty">尚未发现可用设备</p>
        )}
      </div>
    </section>
  );
}
