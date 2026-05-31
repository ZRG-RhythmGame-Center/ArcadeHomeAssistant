import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import * as filesApi from '../services/filesApi';
import {
  useFileRoots,
  useFileListing,
  useUpload,
  useDelete,
  useRename,
  useMove,
  useDownload,
} from './useFiles';

// Each test gets its own QueryClient with retries off so failures don't loop.
function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return { client, Wrapper };
}

describe('useFileRoots', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('returns roots on success', async () => {
    const data = [
      { id: 'r1', name: 'Music', readOnly: false },
      { id: 'r2', name: 'Photos', readOnly: true },
    ];
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue(data);

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useFileRoots(), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(data);
  });
});

describe('useFileListing', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('passes rootId and path to getFileListing and returns the result', async () => {
    const listing = {
      entries: [{ name: 'a.txt', kind: 'file' as const, size: 5, modified: '2026-05-31T00:00:00Z' }],
      total: 1,
      truncated: false,
    };
    const spy = vi.spyOn(filesApi, 'getFileListing').mockResolvedValue(listing);

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useFileListing('r1', 'sub/dir'), { wrapper: Wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(spy).toHaveBeenCalledWith('r1', 'sub/dir');
    expect(result.current.data).toEqual(listing);
  });

  it('does not fire when rootId is null', () => {
    const spy = vi.spyOn(filesApi, 'getFileListing').mockResolvedValue({
      entries: [],
      total: 0,
      truncated: false,
    });

    const { Wrapper } = makeWrapper();
    renderHook(() => useFileListing(null, ''), { wrapper: Wrapper });

    expect(spy).not.toHaveBeenCalled();
  });
});

describe('useUpload', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls uploadFile with the supplied args and invalidates the listing', async () => {
    const upload = vi
      .spyOn(filesApi, 'uploadFile')
      .mockResolvedValue({ rootId: 'r1', path: 'docs/a.txt', size: 1, overwritten: false });

    const { client, Wrapper } = makeWrapper();
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useUpload(), { wrapper: Wrapper });

    const file = new File(['hi'], 'a.txt');
    await act(async () => {
      await result.current.mutateAsync({
        rootId: 'r1',
        path: 'docs/a.txt',
        file,
        overwrite: false,
      });
    });

    expect(upload).toHaveBeenCalledWith('r1', 'docs/a.txt', file, false);
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ['files', 'listing', 'r1'],
    });
  });
});

describe('useDelete', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls deleteFile and invalidates listing for the affected root', async () => {
    const del = vi
      .spyOn(filesApi, 'deleteFile')
      .mockResolvedValue({ rootId: 'r1', path: 'a.txt' });

    const { client, Wrapper } = makeWrapper();
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useDelete(), { wrapper: Wrapper });

    await act(async () => {
      await result.current.mutateAsync({ rootId: 'r1', path: 'a.txt' });
    });

    expect(del).toHaveBeenCalledWith('r1', 'a.txt');
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ['files', 'listing', 'r1'],
    });
  });
});

describe('useRename', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls renameFile and invalidates listing for the affected root', async () => {
    const ren = vi
      .spyOn(filesApi, 'renameFile')
      .mockResolvedValue({ rootId: 'r1', fromPath: 'a.txt', toPath: 'b.txt' });

    const { client, Wrapper } = makeWrapper();
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useRename(), { wrapper: Wrapper });

    await act(async () => {
      await result.current.mutateAsync({ rootId: 'r1', path: 'a.txt', newName: 'b.txt' });
    });

    expect(ren).toHaveBeenCalledWith('r1', 'a.txt', 'b.txt');
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ['files', 'listing', 'r1'],
    });
  });
});

describe('useMove', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('calls moveFile and invalidates listing for the affected root', async () => {
    const mv = vi
      .spyOn(filesApi, 'moveFile')
      .mockResolvedValue({ rootId: 'r1', fromPath: 'a.txt', toPath: 'sub/a.txt' });

    const { client, Wrapper } = makeWrapper();
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useMove(), { wrapper: Wrapper });

    await act(async () => {
      await result.current.mutateAsync({
        rootId: 'r1',
        fromPath: 'a.txt',
        toPath: 'sub/a.txt',
      });
    });

    expect(mv).toHaveBeenCalledWith('r1', 'a.txt', 'sub/a.txt');
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: ['files', 'listing', 'r1'],
    });
  });
});

describe('useDownload', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('fetches blob and triggers an anchor click with the basename of the path', async () => {
    const blob = new Blob(['abc'], { type: 'application/octet-stream' });
    vi.spyOn(filesApi, 'downloadFile').mockResolvedValue(blob);

    // jsdom does not implement createObjectURL/revokeObjectURL — stub them.
    const createObjectURL = vi.fn(() => 'blob:mock');
    const revokeObjectURL = vi.fn();
    const originalCreate = URL.createObjectURL;
    const originalRevoke = URL.revokeObjectURL;
    URL.createObjectURL = createObjectURL as unknown as typeof URL.createObjectURL;
    URL.revokeObjectURL = revokeObjectURL as unknown as typeof URL.revokeObjectURL;

    const click = vi.fn();
    const originalCreateElement = document.createElement.bind(document);
    const createElementSpy = vi
      .spyOn(document, 'createElement')
      .mockImplementation((tag: string) => {
        if (tag === 'a') {
          const a = originalCreateElement('a') as HTMLAnchorElement;
          a.click = click;
          return a;
        }
        return originalCreateElement(tag);
      });

    try {
      const { Wrapper } = makeWrapper();
      const { result } = renderHook(() => useDownload(), { wrapper: Wrapper });

      await act(async () => {
        await result.current({ rootId: 'r1', path: 'sub/dir/file.txt' });
      });

      expect(createObjectURL).toHaveBeenCalledWith(blob);
      expect(click).toHaveBeenCalledTimes(1);
      expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock');
    } finally {
      URL.createObjectURL = originalCreate;
      URL.revokeObjectURL = originalRevoke;
      createElementSpy.mockRestore();
    }
  });
});
