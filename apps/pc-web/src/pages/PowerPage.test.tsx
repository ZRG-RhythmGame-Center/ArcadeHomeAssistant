import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/powerApi', () => ({
  getAgentStatus: vi.fn(),
  getRemoteShutdownStatus: vi.fn(),
  executeRemoteShutdown: vi.fn(),
}));

import * as powerApi from '../services/powerApi';
import { useAgentStore } from '../stores/agentStore';
import { PowerPage } from './PowerPage';

function renderWithProviders(ui: ReactNode) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={client}>{ui}</QueryClientProvider>
  );
}

describe('PowerPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAgentStore.setState({ baseUrl: 'http://127.0.0.1:8765' });
    vi.mocked(powerApi.getRemoteShutdownStatus).mockResolvedValue({
      available: true,
      state: 'idle',
      error: null,
    });
  });

  it('disables shutdown when capability is false', async () => {
    vi.mocked(powerApi.getAgentStatus).mockResolvedValue({
      machineName: 'PC-A',
      version: '1',
      uptimeSeconds: 1,
      baseUrl: 'http://pc-a:8765',
      capabilities: { remoteShutdown: false },
    });

    renderWithProviders(<PowerPage />);

    expect(await screen.findByText('PC-A')).toBeInTheDocument();
    const button = screen.getByRole('button', { name: '远程关机' });
    await waitFor(() => expect(button).toBeDisabled());
  });

  it('requires confirmation and sends bearer token for immediate shutdown', async () => {
    vi.mocked(powerApi.getAgentStatus).mockResolvedValue({
      machineName: 'PC-A',
      version: '1',
      uptimeSeconds: 1,
      baseUrl: 'http://pc-a:8765',
      capabilities: { remoteShutdown: true },
    });
    vi.mocked(powerApi.executeRemoteShutdown).mockResolvedValue({
      available: true,
      state: 'executing',
      error: null,
    });

    renderWithProviders(<PowerPage />);

    const openButton = await screen.findByRole('button', { name: '远程关机' });
    await waitFor(() => expect(openButton).not.toBeDisabled());
    fireEvent.click(openButton);

    expect(screen.getByText(/确认后将立即关机/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('控制令牌'), {
      target: { value: 'secret-token' },
    });
    fireEvent.click(screen.getByRole('button', { name: '确认关机' }));

    await waitFor(() => {
      expect(powerApi.executeRemoteShutdown).toHaveBeenCalledWith('secret-token');
    });
  });
});
