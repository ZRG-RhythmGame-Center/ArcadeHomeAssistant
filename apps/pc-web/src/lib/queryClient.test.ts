import { describe, it, expect } from 'vitest';
import { queryClient, DEFAULT_STALE_TIME, DEFAULT_RETRY } from './queryClient';

describe('queryClient', () => {
  it('exposes constants matching plan defaults', () => {
    expect(DEFAULT_STALE_TIME).toBe(30_000);
    expect(DEFAULT_RETRY).toBe(1);
  });

  it('configures default options on the QueryClient instance', () => {
    const defaults = queryClient.getDefaultOptions();
    expect(defaults.queries?.staleTime).toBe(30_000);
    expect(defaults.queries?.retry).toBe(1);
  });
});
