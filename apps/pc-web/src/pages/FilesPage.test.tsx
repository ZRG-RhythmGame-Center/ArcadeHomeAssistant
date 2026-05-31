import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import * as filesApi from '../services/filesApi';
import { FilesPage } from './FilesPage';

function renderPage() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<FilesPage />, { wrapper: Wrapper });
}

describe('FilesPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders root list and selects the first root by default', async () => {
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue([
      { id: 'r1', name: 'Music', readOnly: false },
      { id: 'r2', name: 'Photos', readOnly: true },
    ]);
    vi.spyOn(filesApi, 'getFileListing').mockResolvedValue({
      entries: [
        { name: 'song.mp3', kind: 'file', size: 1234, modified: '2026-05-31T00:00:00Z' },
        { name: 'albums', kind: 'dir', size: null, modified: '2026-05-31T00:00:00Z' },
      ],
      total: 2,
      truncated: false,
    });

    renderPage();

    expect(await screen.findByRole('button', { name: /Music/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Photos/ })).toBeInTheDocument();

    expect(await screen.findByText('song.mp3')).toBeInTheDocument();
    expect(screen.getByText('albums')).toBeInTheDocument();
  });

  it('shows truncated banner when listing is truncated', async () => {
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue([
      { id: 'r1', name: 'Music', readOnly: false },
    ]);
    vi.spyOn(filesApi, 'getFileListing').mockResolvedValue({
      entries: [{ name: 'a.txt', kind: 'file', size: 1, modified: '2026-05-31T00:00:00Z' }],
      total: 999,
      truncated: true,
    });

    renderPage();

    expect(await screen.findByText(/truncated/i)).toBeInTheDocument();
  });

  it('navigates into a directory when its name is clicked', async () => {
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue([
      { id: 'r1', name: 'Music', readOnly: false },
    ]);
    const listing = vi
      .spyOn(filesApi, 'getFileListing')
      .mockResolvedValueOnce({
        entries: [{ name: 'albums', kind: 'dir', size: null, modified: '2026-05-31T00:00:00Z' }],
        total: 1,
        truncated: false,
      })
      .mockResolvedValueOnce({
        entries: [{ name: 'rock.mp3', kind: 'file', size: 1, modified: '2026-05-31T00:00:00Z' }],
        total: 1,
        truncated: false,
      });

    renderPage();

    const albumsRow = await screen.findByRole('button', { name: 'albums' });
    await userEvent.click(albumsRow);

    expect(await screen.findByText('rock.mp3')).toBeInTheDocument();
    expect(listing).toHaveBeenCalledWith('r1', '');
    expect(listing).toHaveBeenCalledWith('r1', 'albums');
  });

  it('uploads a file via the file input and invalidates the listing', async () => {
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue([
      { id: 'r1', name: 'Music', readOnly: false },
    ]);
    vi.spyOn(filesApi, 'getFileListing').mockResolvedValue({
      entries: [],
      total: 0,
      truncated: false,
    });
    const upload = vi.spyOn(filesApi, 'uploadFile').mockResolvedValue({
      rootId: 'r1',
      path: 'a.txt',
      size: 2,
      overwritten: false,
    });

    renderPage();

    // wait for initial state
    await screen.findByRole('button', { name: /Music/ });

    const input = screen.getByLabelText(/upload/i) as HTMLInputElement;
    const file = new File(['hi'], 'a.txt', { type: 'text/plain' });
    await userEvent.upload(input, file);

    await waitFor(() =>
      expect(upload).toHaveBeenCalledWith('r1', 'a.txt', file, false),
    );
  });

  it('asks for confirmation before deleting and skips the API call when canceled', async () => {
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue([
      { id: 'r1', name: 'Music', readOnly: false },
    ]);
    vi.spyOn(filesApi, 'getFileListing').mockResolvedValue({
      entries: [{ name: 'a.txt', kind: 'file', size: 1, modified: '2026-05-31T00:00:00Z' }],
      total: 1,
      truncated: false,
    });
    const del = vi.spyOn(filesApi, 'deleteFile').mockResolvedValue({ rootId: 'r1', path: 'a.txt' });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);

    renderPage();

    const row = await screen.findByRole('row', { name: /a\.txt/ });
    await userEvent.click(within(row).getByRole('button', { name: /delete/i }));

    expect(confirm).toHaveBeenCalledTimes(1);
    expect(del).not.toHaveBeenCalled();
  });

  it('calls deleteFile when the user confirms', async () => {
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue([
      { id: 'r1', name: 'Music', readOnly: false },
    ]);
    vi.spyOn(filesApi, 'getFileListing').mockResolvedValue({
      entries: [{ name: 'a.txt', kind: 'file', size: 1, modified: '2026-05-31T00:00:00Z' }],
      total: 1,
      truncated: false,
    });
    const del = vi.spyOn(filesApi, 'deleteFile').mockResolvedValue({ rootId: 'r1', path: 'a.txt' });
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    renderPage();

    const row = await screen.findByRole('row', { name: /a\.txt/ });
    await userEvent.click(within(row).getByRole('button', { name: /delete/i }));

    await waitFor(() => expect(del).toHaveBeenCalledWith('r1', 'a.txt'));
  });

  it('renames a file using window.prompt for the new name', async () => {
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue([
      { id: 'r1', name: 'Music', readOnly: false },
    ]);
    vi.spyOn(filesApi, 'getFileListing').mockResolvedValue({
      entries: [{ name: 'a.txt', kind: 'file', size: 1, modified: '2026-05-31T00:00:00Z' }],
      total: 1,
      truncated: false,
    });
    const ren = vi
      .spyOn(filesApi, 'renameFile')
      .mockResolvedValue({ rootId: 'r1', fromPath: 'a.txt', toPath: 'b.txt' });
    vi.spyOn(window, 'prompt').mockReturnValue('b.txt');
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    renderPage();

    const row = await screen.findByRole('row', { name: /a\.txt/ });
    await userEvent.click(within(row).getByRole('button', { name: /rename/i }));

    await waitFor(() => expect(ren).toHaveBeenCalledWith('r1', 'a.txt', 'b.txt'));
  });

  it('moves a file within the same root', async () => {
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue([
      { id: 'r1', name: 'Music', readOnly: false },
    ]);
    vi.spyOn(filesApi, 'getFileListing').mockResolvedValue({
      entries: [{ name: 'a.txt', kind: 'file', size: 1, modified: '2026-05-31T00:00:00Z' }],
      total: 1,
      truncated: false,
    });
    const mv = vi
      .spyOn(filesApi, 'moveFile')
      .mockResolvedValue({ rootId: 'r1', fromPath: 'a.txt', toPath: 'sub/a.txt' });
    vi.spyOn(window, 'prompt').mockReturnValue('sub/a.txt');
    vi.spyOn(window, 'confirm').mockReturnValue(true);

    renderPage();

    const row = await screen.findByRole('row', { name: /a\.txt/ });
    await userEvent.click(within(row).getByRole('button', { name: /^move$/i }));

    await waitFor(() => expect(mv).toHaveBeenCalledWith('r1', 'a.txt', 'sub/a.txt'));
  });

  it('disables write actions for read-only roots', async () => {
    vi.spyOn(filesApi, 'getFileRoots').mockResolvedValue([
      { id: 'r2', name: 'Photos', readOnly: true },
    ]);
    vi.spyOn(filesApi, 'getFileListing').mockResolvedValue({
      entries: [{ name: 'a.jpg', kind: 'file', size: 1, modified: '2026-05-31T00:00:00Z' }],
      total: 1,
      truncated: false,
    });

    renderPage();

    const row = await screen.findByRole('row', { name: /a\.jpg/ });
    expect(within(row).getByRole('button', { name: /delete/i })).toBeDisabled();
    expect(within(row).getByRole('button', { name: /rename/i })).toBeDisabled();
    expect(within(row).getByRole('button', { name: /^move$/i })).toBeDisabled();

    const upload = screen.getByLabelText(/upload/i) as HTMLInputElement;
    expect(upload).toBeDisabled();
  });
});
