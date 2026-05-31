import { describe, it, expect, beforeEach } from 'vitest';
import { useAgentStore } from './agentStore';

describe('agentStore', () => {
  beforeEach(() => {
    // Reset store to its module defaults so each test gets a clean slate.
    useAgentStore.setState({
      baseUrl: 'http://127.0.0.1:8765',
    });
  });

  it('exposes a default baseUrl pointing at the loopback agent', () => {
    expect(useAgentStore.getState().baseUrl).toBe('http://127.0.0.1:8765');
  });

  it('updates baseUrl via setBaseUrl', () => {
    useAgentStore.getState().setBaseUrl('http://192.168.1.20:8765');
    expect(useAgentStore.getState().baseUrl).toBe('http://192.168.1.20:8765');
  });
});
