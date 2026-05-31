import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { EventEnvelope } from '../lib/eventStream';

/**
 * Replace EventStream with a fake we can poke from tests.
 * Use vi.hoisted so the class definition is available when vi.mock runs
 * (vi.mock factories are hoisted above all imports).
 */
const { fakeStreams, FakeEventStream } = vi.hoisted(() => {
  type Listener = (e: { type: string; payload: unknown; timestamp: string }) => void;
  type ReconnectListener = () => void;

  const fakeStreams: FakeEventStream[] = [];

  class FakeEventStream {
    listeners: Set<Listener> = new Set();
    reconnectListeners: Set<ReconnectListener> = new Set();
    connectCount = 0;
    disconnectCount = 0;

    constructor() {
      fakeStreams.push(this);
    }

    subscribe(handler: Listener): () => void {
      this.listeners.add(handler);
      return () => {
        this.listeners.delete(handler);
      };
    }

    onReconnect(handler: ReconnectListener): () => void {
      this.reconnectListeners.add(handler);
      return () => {
        this.reconnectListeners.delete(handler);
      };
    }

    connect(): void {
      this.connectCount += 1;
    }

    disconnect(): void {
      this.disconnectCount += 1;
    }

    emit(event: { type: string; payload: unknown; timestamp: string }): void {
      this.listeners.forEach((cb) => cb(event));
    }

    emitReconnect(): void {
      this.reconnectListeners.forEach((cb) => cb());
    }
  }

  return { fakeStreams, FakeEventStream };
});

vi.mock('../lib/eventStream', async () => {
  const actual = await vi.importActual<typeof import('../lib/eventStream')>(
    '../lib/eventStream',
  );
  return {
    ...actual,
    EventStream: FakeEventStream,
  };
});

import { useEventStream } from './useEventStream';

function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return React.createElement(
      QueryClientProvider,
      { client },
      children,
    );
  };
}

describe('useEventStream', () => {
  let client: QueryClient;
  // Vitest's MockInstance type doesn't compose with QueryClient's overloaded
  // invalidateQueries signature under tsc strict; we treat the spy as a generic
  // mock to keep the assertions readable.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let invalidateSpy: any;

  beforeEach(() => {
    fakeStreams.length = 0;
    client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    invalidateSpy = vi.spyOn(client, 'invalidateQueries');
  });

  afterEach(() => {
    invalidateSpy.mockRestore();
    client.clear();
  });

  it('connects an EventStream on mount and disconnects on unmount', () => {
    const { unmount } = renderHook(() => useEventStream(), {
      wrapper: makeWrapper(client),
    });

    expect(fakeStreams).toHaveLength(1);
    expect(fakeStreams[0].connectCount).toBe(1);

    unmount();
    expect(fakeStreams[0].disconnectCount).toBe(1);
  });

  it('invalidates ["audio","state"] on audio.state event', () => {
    renderHook(() => useEventStream(), { wrapper: makeWrapper(client) });

    const event: EventEnvelope = {
      type: 'audio.state',
      payload: { masterVolume: 0.5 },
      timestamp: '2026-05-31T00:00:00Z',
    };
    fakeStreams[0].emit(event);

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['audio', 'state'] });
  });

  it('invalidates ["audio"] on audio.device.changed event', () => {
    renderHook(() => useEventStream(), { wrapper: makeWrapper(client) });

    fakeStreams[0].emit({
      type: 'audio.device.changed',
      payload: [],
      timestamp: '2026-05-31T00:00:00Z',
    });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['audio'] });
  });

  it('invalidates ["files","listing"] on file.created event', () => {
    renderHook(() => useEventStream(), { wrapper: makeWrapper(client) });

    fakeStreams[0].emit({
      type: 'file.created',
      payload: { rootId: 'r', path: 'a.txt' },
      timestamp: '2026-05-31T00:00:00Z',
    });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['files', 'listing'] });
  });

  it.each([
    ['file.deleted'],
    ['file.renamed'],
    ['file.moved'],
  ])('invalidates ["files","listing"] on %s event', (type) => {
    renderHook(() => useEventStream(), { wrapper: makeWrapper(client) });

    fakeStreams[0].emit({
      type,
      payload: {},
      timestamp: '2026-05-31T00:00:00Z',
    });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['files', 'listing'] });
  });

  it('does NOT invalidate anything for unknown event types', () => {
    renderHook(() => useEventStream(), { wrapper: makeWrapper(client) });

    fakeStreams[0].emit({
      type: 'device.unavailable',
      payload: {},
      timestamp: '2026-05-31T00:00:00Z',
    });

    expect(invalidateSpy).not.toHaveBeenCalled();
  });

  it('on reconnect, invalidates audio + files queries (only those, not all)', () => {
    renderHook(() => useEventStream(), { wrapper: makeWrapper(client) });

    fakeStreams[0].emitReconnect();

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['audio'] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['files'] });
    // Sanity: not the catch-all `invalidateQueries()` form.
    expect(invalidateSpy).not.toHaveBeenCalledWith();
  });
});
