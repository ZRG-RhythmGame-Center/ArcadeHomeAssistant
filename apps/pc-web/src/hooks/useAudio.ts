import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getAudioDevices,
  getAudioState,
  setMute,
  setVolume,
  switchDevice,
  type AudioDevice,
  type AudioState,
} from '../services/audioApi';

/**
 * Query key tuples are exported so tests and other hooks can invalidate the
 * exact same cache slot. Treat these as the single source of truth for the
 * audio-feature cache topology.
 */
export const audioQueryKeys = {
  all: ['audio'] as const,
  state: ['audio', 'state'] as const,
  devices: ['audio', 'devices'] as const,
};

/**
 * Cached read of `GET /api/audio/state`.
 *
 * `staleTime: 5_000` keeps the slider responsive after a successful mutation —
 * we always invalidate after a write, but the 5s window means rapid
 * re-renders during slider drag don't refetch on every mouse-up.
 */
export function useAudioState() {
  return useQuery<AudioState>({
    queryKey: audioQueryKeys.state,
    queryFn: getAudioState,
    staleTime: 5_000,
  });
}

/**
 * Cached read of `GET /api/audio/devices`. Devices change rarely (plug / unplug
 * events are pushed via WebSocket in T20), so a 10s stale window is fine for
 * the polled-only path.
 */
export function useAudioDevices() {
  return useQuery<AudioDevice[]>({
    queryKey: audioQueryKeys.devices,
    queryFn: getAudioDevices,
    staleTime: 10_000,
  });
}

/**
 * Mutation wrapper around `setVolume`. On success, invalidates the audio-state
 * query so the slider snaps to whatever the agent actually applied (the OS
 * may round to the nearest scalar step, so optimistic updates can drift).
 */
export function useSetVolume() {
  const queryClient = useQueryClient();
  return useMutation({
    // Wrap to forward only `level` — TanStack Query v5 passes a context
    // object as the second mutationFn arg, which would otherwise leak into
    // the API call's argument list.
    mutationFn: (level: number) => setVolume(level),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: audioQueryKeys.state });
    },
  });
}

/** Mutation wrapper around `setMute`. */
export function useSetMute() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (muted: boolean) => setMute(muted),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: audioQueryKeys.state });
    },
  });
}

/**
 * Mutation wrapper around `switchDevice`. Invalidates the whole `['audio']`
 * subtree because both `state.defaultDeviceId` AND the per-device `isDefault`
 * flags need to refresh after a switch.
 */
export function useSwitchDevice() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (deviceId: string) => switchDevice(deviceId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: audioQueryKeys.all });
    },
  });
}
