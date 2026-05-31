import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { EventStream, computeBackoffMs, MAX_BACKOFF_MS } from './eventStream';
import { useAgentStore } from '../stores/agentStore';

/**
 * Mock WebSocket implementation for tests.
 * Records constructor calls and lets each test trigger open/message/close/error.
 */
class MockWebSocket {
  static instances: MockWebSocket[] = [];
  static OPEN = 1;
  static CLOSED = 3;

  url: string;
  readyState = 0;
  onopen: ((ev: Event) => void) | null = null;
  onmessage: ((ev: MessageEvent) => void) | null = null;
  onclose: ((ev: CloseEvent) => void) | null = null;
  onerror: ((ev: Event) => void) | null = null;
  closeCalled = false;

  constructor(url: string) {
    this.url = url;
    MockWebSocket.instances.push(this);
  }

  triggerOpen() {
    this.readyState = MockWebSocket.OPEN;
    this.onopen?.(new Event('open'));
  }

  triggerMessage(data: unknown) {
    const payload = typeof data === 'string' ? data : JSON.stringify(data);
    this.onmessage?.({ data: payload } as MessageEvent);
  }

  triggerClose() {
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.({} as CloseEvent);
  }

  close() {
    this.closeCalled = true;
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.({} as CloseEvent);
  }
}

const originalWebSocket = globalThis.WebSocket;

describe('computeBackoffMs', () => {
  it('starts at 1000ms for the first retry', () => {
    expect(computeBackoffMs(0)).toBe(1000);
  });

  it('doubles each attempt', () => {
    expect(computeBackoffMs(1)).toBe(2000);
    expect(computeBackoffMs(2)).toBe(4000);
    expect(computeBackoffMs(3)).toBe(8000);
    expect(computeBackoffMs(4)).toBe(16000);
  });

  it('caps at 30s', () => {
    expect(computeBackoffMs(5)).toBe(MAX_BACKOFF_MS);
    expect(computeBackoffMs(10)).toBe(MAX_BACKOFF_MS);
    expect(MAX_BACKOFF_MS).toBe(30_000);
  });
});

describe('EventStream', () => {
  beforeEach(() => {
    MockWebSocket.instances = [];
    // @ts-expect-error — replace browser global with mock
    globalThis.WebSocket = MockWebSocket;
    useAgentStore.setState({
      baseUrl: 'http://127.0.0.1:8765',
    });
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    globalThis.WebSocket = originalWebSocket;
  });

  it('connects to ws:// URL derived from baseUrl', () => {
    useAgentStore.setState({ baseUrl: 'http://192.168.1.5:8765' });
    const stream = new EventStream();
    stream.connect();

    expect(MockWebSocket.instances).toHaveLength(1);
    expect(MockWebSocket.instances[0].url).toBe(
      'ws://192.168.1.5:8765/api/events',
    );
    stream.disconnect();
  });

  it('uses the loopback baseUrl when none has been set explicitly', () => {
    const stream = new EventStream();
    stream.connect();

    expect(MockWebSocket.instances[0].url).toBe(
      'ws://127.0.0.1:8765/api/events',
    );
    stream.disconnect();
  });

  it('translates https baseUrl to wss for the websocket scheme', () => {
    useAgentStore.setState({ baseUrl: 'https://192.168.1.5:8765' });
    const stream = new EventStream();
    stream.connect();

    expect(MockWebSocket.instances[0].url.startsWith('wss://')).toBe(true);
    stream.disconnect();
  });

  it('parses incoming JSON frames and forwards them to subscribers', () => {
    const stream = new EventStream();
    const handler = vi.fn();
    stream.subscribe(handler);
    stream.connect();

    const ws = MockWebSocket.instances[0];
    ws.triggerOpen();
    ws.triggerMessage({
      type: 'audio.state',
      payload: { masterVolume: 0.5 },
      timestamp: '2026-05-31T00:00:00Z',
    });

    expect(handler).toHaveBeenCalledWith({
      type: 'audio.state',
      payload: { masterVolume: 0.5 },
      timestamp: '2026-05-31T00:00:00Z',
    });

    stream.disconnect();
  });

  it('drops malformed frames silently and keeps the socket open', () => {
    const stream = new EventStream();
    const handler = vi.fn();
    stream.subscribe(handler);
    stream.connect();

    const ws = MockWebSocket.instances[0];
    ws.triggerOpen();
    ws.triggerMessage('not-json');

    expect(handler).not.toHaveBeenCalled();
    expect(ws.closeCalled).toBe(false);

    stream.disconnect();
  });

  it('subscribe returns an unsubscribe function', () => {
    const stream = new EventStream();
    const handler = vi.fn();
    const unsubscribe = stream.subscribe(handler);
    stream.connect();

    const ws = MockWebSocket.instances[0];
    ws.triggerOpen();

    unsubscribe();
    ws.triggerMessage({
      type: 'audio.state',
      payload: {},
      timestamp: '2026-05-31T00:00:00Z',
    });

    expect(handler).not.toHaveBeenCalled();
    stream.disconnect();
  });

  it('reconnects after 1s on close', () => {
    const stream = new EventStream();
    stream.connect();

    const first = MockWebSocket.instances[0];
    first.triggerClose();

    expect(MockWebSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(999);
    expect(MockWebSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(MockWebSocket.instances).toHaveLength(2);

    stream.disconnect();
  });

  it('uses exponential backoff across consecutive failures (1s, 2s, 4s)', () => {
    const stream = new EventStream();
    stream.connect();

    // 1st close → reconnect after 1s
    MockWebSocket.instances[0].triggerClose();
    vi.advanceTimersByTime(1000);
    expect(MockWebSocket.instances).toHaveLength(2);

    // 2nd close → reconnect after 2s
    MockWebSocket.instances[1].triggerClose();
    vi.advanceTimersByTime(1999);
    expect(MockWebSocket.instances).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(MockWebSocket.instances).toHaveLength(3);

    // 3rd close → reconnect after 4s
    MockWebSocket.instances[2].triggerClose();
    vi.advanceTimersByTime(3999);
    expect(MockWebSocket.instances).toHaveLength(3);
    vi.advanceTimersByTime(1);
    expect(MockWebSocket.instances).toHaveLength(4);

    stream.disconnect();
  });

  it('caps backoff at 30s after enough failures', () => {
    const stream = new EventStream();
    stream.connect();

    // Burn through 1s, 2s, 4s, 8s, 16s = 5 failures, next should be 30s
    for (let i = 0; i < 5; i++) {
      MockWebSocket.instances[i].triggerClose();
      vi.runOnlyPendingTimers();
    }
    expect(MockWebSocket.instances).toHaveLength(6);

    // 6th close should schedule a 30s retry, not 32s
    MockWebSocket.instances[5].triggerClose();
    vi.advanceTimersByTime(29_999);
    expect(MockWebSocket.instances).toHaveLength(6);
    vi.advanceTimersByTime(1);
    expect(MockWebSocket.instances).toHaveLength(7);

    stream.disconnect();
  });

  it('resets backoff after a successful connection', () => {
    const stream = new EventStream();
    stream.connect();

    // Two failures → backoff at 4s
    MockWebSocket.instances[0].triggerClose();
    vi.advanceTimersByTime(1000);
    MockWebSocket.instances[1].triggerClose();
    vi.advanceTimersByTime(2000);
    expect(MockWebSocket.instances).toHaveLength(3);

    // Connection 3 succeeds → reset
    MockWebSocket.instances[2].triggerOpen();
    MockWebSocket.instances[2].triggerClose();
    // Should retry after 1s, not 4s
    vi.advanceTimersByTime(1000);
    expect(MockWebSocket.instances).toHaveLength(4);

    stream.disconnect();
  });

  it('disconnect prevents future reconnect attempts', () => {
    const stream = new EventStream();
    stream.connect();

    const ws = MockWebSocket.instances[0];
    stream.disconnect();
    expect(ws.closeCalled).toBe(true);

    // Even after timer flushes, no new instance
    vi.advanceTimersByTime(60_000);
    expect(MockWebSocket.instances).toHaveLength(1);
  });

  it('notifies subscribers on reconnect via onReconnect callback', () => {
    const stream = new EventStream();
    const onReconnect = vi.fn();
    stream.onReconnect(onReconnect);

    stream.connect();
    MockWebSocket.instances[0].triggerOpen(); // initial open is NOT a reconnect
    expect(onReconnect).not.toHaveBeenCalled();

    MockWebSocket.instances[0].triggerClose();
    vi.advanceTimersByTime(1000);
    MockWebSocket.instances[1].triggerOpen(); // this IS a reconnect
    expect(onReconnect).toHaveBeenCalledTimes(1);

    stream.disconnect();
  });
});
