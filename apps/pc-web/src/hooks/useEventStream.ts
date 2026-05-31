import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { EventStream, type EventEnvelope } from '../lib/eventStream';

/**
 * Subscribe to the agent's `/api/events` WebSocket and keep TanStack Query
 * caches in sync via selective `invalidateQueries` calls.
 *
 * Routing rules (must stay in sync with `EventTypes.cs` on the agent):
 * - `audio.state`           → invalidate `['audio', 'state']`
 * - `audio.device.changed`  → invalidate `['audio']` (covers state + device list)
 * - `file.*`                → invalidate `['files', 'listing']`
 *
 * Reconnect handling: once the socket re-opens after a disconnect, we
 * invalidate `['audio']` and `['files']` (NOT a global flush) to recover
 * any state changes that happened while we were offline.
 *
 * Mount this hook once at the root component so it owns a single connection
 * for the whole app. Re-mounting creates a new socket.
 */
export function useEventStream(): void {
  const queryClient = useQueryClient();

  useEffect(() => {
    const stream = new EventStream();

    const handleEvent = (envelope: EventEnvelope) => {
      const { type } = envelope;
      if (type === 'audio.state') {
        queryClient.invalidateQueries({ queryKey: ['audio', 'state'] });
        return;
      }
      if (type === 'audio.device.changed') {
        queryClient.invalidateQueries({ queryKey: ['audio'] });
        return;
      }
      if (type.startsWith('file.')) {
        queryClient.invalidateQueries({ queryKey: ['files', 'listing'] });
        return;
      }
      // Other server-side event types (e.g. `device.unavailable`) are not
      // mapped to a query key — ignored on purpose.
    };

    const handleReconnect = () => {
      // After a network blip we may have missed events; refresh both feature
      // areas without invalidating EVERY query.
      queryClient.invalidateQueries({ queryKey: ['audio'] });
      queryClient.invalidateQueries({ queryKey: ['files'] });
    };

    const unsubscribe = stream.subscribe(handleEvent);
    const unsubscribeReconnect = stream.onReconnect(handleReconnect);
    stream.connect();

    return () => {
      unsubscribe();
      unsubscribeReconnect();
      stream.disconnect();
    };
  }, [queryClient]);
}
