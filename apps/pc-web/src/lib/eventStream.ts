import { useAgentStore } from '../stores/agentStore';

/**
 * Wire-format envelope for every WebSocket frame the agent sends.
 * Mirrors `Realtime/EventEnvelope.cs` on the Windows Agent.
 */
export interface EventEnvelope {
  type: string;
  payload: unknown;
  timestamp: string;
}

/** Caps the exponential backoff at 30 seconds. */
export const MAX_BACKOFF_MS = 30_000;
/** First retry delay; doubles each consecutive failure up to MAX_BACKOFF_MS. */
export const INITIAL_BACKOFF_MS = 1_000;

type EventHandler = (event: EventEnvelope) => void;
type ReconnectHandler = () => void;

/**
 * Compute the backoff delay (ms) for the Nth consecutive failure.
 * attempt 0 → 1s, 1 → 2s, 2 → 4s, 3 → 8s, 4 → 16s, ≥5 → 30s.
 */
export function computeBackoffMs(attempt: number): number {
  const ms = INITIAL_BACKOFF_MS * Math.pow(2, attempt);
  return Math.min(ms, MAX_BACKOFF_MS);
}

/**
 * Browser → Agent realtime channel.
 *
 * Lifecycle: `connect()` opens a WebSocket using `baseUrl` from the Zustand
 * `agentStore`. On any close (network blip, server restart) it schedules a
 * reconnect using exponential backoff (1s → 2s → ... → 30s). A successful
 * open resets the backoff counter.
 *
 * Subscribers receive parsed `EventEnvelope`s via `subscribe(handler)`.
 * `onReconnect(cb)` fires after a successful reopen (NOT the initial connect)
 * so callers can refresh stale caches.
 *
 * `disconnect()` is idempotent and stops further reconnect attempts.
 */
export class EventStream {
  private socket: WebSocket | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private failureCount = 0;
  private hasConnectedOnce = false;
  private explicitlyClosed = false;
  private readonly handlers = new Set<EventHandler>();
  private readonly reconnectHandlers = new Set<ReconnectHandler>();

  /** Add an envelope subscriber. Returns an unsubscribe function. */
  subscribe(handler: EventHandler): () => void {
    this.handlers.add(handler);
    return () => {
      this.handlers.delete(handler);
    };
  }

  /** Add a reconnect listener. Fires after each successful re-open. */
  onReconnect(handler: ReconnectHandler): () => void {
    this.reconnectHandlers.add(handler);
    return () => {
      this.reconnectHandlers.delete(handler);
    };
  }

  /** Open the socket. No-op if a socket is already pending or connected. */
  connect(): void {
    this.explicitlyClosed = false;
    if (this.socket || this.retryTimer) {
      return;
    }
    this.openSocket();
  }

  /** Close the socket and cancel any pending reconnect. */
  disconnect(): void {
    this.explicitlyClosed = true;
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    if (this.socket) {
      // Detach handlers BEFORE close() so the synthetic close event in mocks
      // (and real browsers' deferred close) doesn't trigger another reconnect.
      this.detachSocketHandlers(this.socket);
      try {
        this.socket.close();
      } catch {
        // ignore — socket already in a terminal state
      }
      this.socket = null;
    }
  }

  private openSocket(): void {
    const { baseUrl } = useAgentStore.getState();
    const url = buildWebSocketUrl(baseUrl);
    const ws = new WebSocket(url);
    this.socket = ws;

    ws.onopen = () => {
      this.failureCount = 0;
      const wasReconnect = this.hasConnectedOnce;
      this.hasConnectedOnce = true;
      if (wasReconnect) {
        this.reconnectHandlers.forEach((cb) => cb());
      }
    };

    ws.onmessage = (ev: MessageEvent) => {
      const data = ev.data;
      if (typeof data !== 'string') {
        return;
      }
      // Agent sends {"type":"ping"} as a heartbeat every 30s. Reply with a
      // pong so the agent keeps the session alive.
      if (data.length < 32 && data.includes('"ping"')) {
        try {
          this.socket?.send('{"type":"pong"}');
        } catch {
          // socket may have closed — ignore
        }
        return;
      }
      let parsed: unknown;
      try {
        parsed = JSON.parse(data);
      } catch {
        // Drop malformed frames; keep the socket open.
        return;
      }
      if (!isEventEnvelope(parsed)) {
        return;
      }
      this.handlers.forEach((cb) => cb(parsed));
    };

    ws.onerror = () => {
      // Errors always precede a close; let onclose do the reconnect bookkeeping.
    };

    ws.onclose = () => {
      this.socket = null;
      if (this.explicitlyClosed) {
        return;
      }
      const delay = computeBackoffMs(this.failureCount);
      this.failureCount += 1;
      this.retryTimer = setTimeout(() => {
        this.retryTimer = null;
        if (this.explicitlyClosed) {
          return;
        }
        this.openSocket();
      }, delay);
    };
  }

  private detachSocketHandlers(ws: WebSocket): void {
    ws.onopen = null;
    ws.onmessage = null;
    ws.onerror = null;
    ws.onclose = null;
  }
}

/**
 * Convert the agent base URL into a WebSocket URL for `/api/events`.
 * `http://` → `ws://`, `https://` → `wss://`. Strips any trailing slash.
 * No auth — this app is LAN-only.
 */
export function buildWebSocketUrl(baseUrl: string): string {
  const trimmed = baseUrl.replace(/\/+$/, '');
  const wsBase = trimmed.replace(/^http(s?):\/\//i, (_match, secure) =>
    secure ? 'wss://' : 'ws://',
  );
  return `${wsBase}/api/events`;
}

function isEventEnvelope(value: unknown): value is EventEnvelope {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const v = value as Record<string, unknown>;
  return typeof v.type === 'string' && typeof v.timestamp === 'string';
}
