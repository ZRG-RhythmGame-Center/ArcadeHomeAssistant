import { QueryClient } from '@tanstack/react-query';

export const DEFAULT_STALE_TIME = 30_000;
export const DEFAULT_RETRY = 1;

/**
 * Shared TanStack Query client for the PC web app.
 *
 * - `staleTime: 30_000` keeps cached agent state warm for half a minute,
 *   matching the heartbeat cadence so we do not refetch on every focus.
 * - `retry: 1` gives one extra attempt in case of a transient LAN blip
 *   without masking a genuinely broken agent for too long.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: DEFAULT_STALE_TIME,
      retry: DEFAULT_RETRY,
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: 0,
    },
  },
});
