import { agentApi } from './agentApi';

/**
 * Master playback state served by `GET /api/audio/state`.
 *
 * Field shapes match the agent's `AudioStateDto` exactly:
 * - `masterVolume` is a scalar in `[0, 1]` (NOT 0..100).
 * - `defaultDeviceId` is `null` when no default playback device is currently
 *   reported by the OS — we MUST treat the slider/device list as disabled in
 *   that case rather than mutating against an undefined target.
 */
export interface AudioState {
  masterVolume: number;
  muted: boolean;
  defaultDeviceId: string | null;
}

/**
 * One row of `GET /api/audio/devices`. `state` is lower-cased on the agent
 * side (`"active"`, `"unplugged"`, ...) so we keep it as a plain string here
 * instead of pinning it to a TS literal union — new device states surfaced by
 * Core Audio shouldn't break the typecheck.
 */
export interface AudioDevice {
  id: string;
  name: string;
  isDefault: boolean;
  state: string;
}

/** GET /api/audio/state. */
export async function getAudioState(): Promise<AudioState> {
  const response = await agentApi.get<AudioState>('/api/audio/state');
  return response.data;
}

/** GET /api/audio/devices. */
export async function getAudioDevices(): Promise<AudioDevice[]> {
  const response = await agentApi.get<AudioDevice[]>('/api/audio/devices');
  return response.data;
}

/**
 * POST /api/audio/volume.
 *
 * @param level scalar in `[0, 1]`. The agent rejects values outside this range
 *   with a 400 `validation_error`; the caller MUST scale `<input type="range">`
 *   percentage values down before invoking.
 */
export async function setVolume(level: number): Promise<void> {
  await agentApi.post('/api/audio/volume', { level });
}

/** POST /api/audio/mute. */
export async function setMute(muted: boolean): Promise<void> {
  await agentApi.post('/api/audio/mute', { muted });
}

/** POST /api/audio/default-device. */
export async function switchDevice(deviceId: string): Promise<void> {
  await agentApi.post('/api/audio/default-device', { deviceId });
}
