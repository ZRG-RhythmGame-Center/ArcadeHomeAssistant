import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import React, { type ReactNode } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock the underlying API module — we want to assert the hooks wire the
// queryClient correctly, not that axios speaks HTTP.
vi.mock('../services/audioApi', () => ({
  getAudioState: vi.fn(),
  getAudioDevices: vi.fn(),
  setVolume: vi.fn(),
  setMute: vi.fn(),
  switchDevice: vi.fn(),
}));

import * as audioApi from '../services/audioApi';
import {
  audioQueryKeys,
  useAudioDevices,
  useAudioState,
  useSetMute,
  useSetVolume,
  useSwitchDevice,
} from './useAudio';

function makeWrapper() {
  // Fresh client per test so cache state never leaks across cases. Disable
  // retries so a rejected mutation surfaces as `isError` immediately rather
  // than triggering the default 1-retry policy and slowing the suite.
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  // Using React.createElement keeps this file as `.ts` (no JSX), per task
  // spec — wrapping in `.tsx` would otherwise be the more idiomatic choice.
  const wrapper = ({ children }: { children: ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children);
  return { client, wrapper };
}

describe('useAudio hooks', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('useAudioState', () => {
    it('returns data on success', async () => {
      vi.mocked(audioApi.getAudioState).mockResolvedValue({
        masterVolume: 0.42,
        muted: false,
        defaultDeviceId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      });
      const { wrapper } = makeWrapper();
      const { result } = renderHook(() => useAudioState(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(result.current.data).toEqual({
        masterVolume: 0.42,
        muted: false,
        defaultDeviceId: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee',
      });
      expect(audioApi.getAudioState).toHaveBeenCalledTimes(1);
    });
  });

  describe('useAudioDevices', () => {
    it('returns the device list on success', async () => {
      vi.mocked(audioApi.getAudioDevices).mockResolvedValue([
        { id: 'd1', name: 'Speakers', isDefault: true, state: 'active' },
        { id: 'd2', name: 'Headset', isDefault: false, state: 'active' },
      ]);
      const { wrapper } = makeWrapper();
      const { result } = renderHook(() => useAudioDevices(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(result.current.data).toHaveLength(2);
      expect(result.current.data?.[0].name).toBe('Speakers');
    });
  });

  describe('useSetVolume', () => {
    it("invalidates ['audio', 'state'] on success", async () => {
      vi.mocked(audioApi.setVolume).mockResolvedValue();
      const { client, wrapper } = makeWrapper();
      const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

      const { result } = renderHook(() => useSetVolume(), { wrapper });
      await result.current.mutateAsync(0.6);

      expect(audioApi.setVolume).toHaveBeenCalledWith(0.6);
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: audioQueryKeys.state,
      });
    });
  });

  describe('useSetMute', () => {
    it("invalidates ['audio', 'state'] on success", async () => {
      vi.mocked(audioApi.setMute).mockResolvedValue();
      const { client, wrapper } = makeWrapper();
      const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

      const { result } = renderHook(() => useSetMute(), { wrapper });
      await result.current.mutateAsync(true);

      expect(audioApi.setMute).toHaveBeenCalledWith(true);
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: audioQueryKeys.state,
      });
    });
  });

  describe('useSwitchDevice', () => {
    it("invalidates the entire ['audio'] subtree on success", async () => {
      vi.mocked(audioApi.switchDevice).mockResolvedValue();
      const { client, wrapper } = makeWrapper();
      const invalidateSpy = vi.spyOn(client, 'invalidateQueries');

      const { result } = renderHook(() => useSwitchDevice(), { wrapper });
      await result.current.mutateAsync('device-xyz');

      expect(audioApi.switchDevice).toHaveBeenCalledWith('device-xyz');
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: audioQueryKeys.all,
      });
    });
  });
});
