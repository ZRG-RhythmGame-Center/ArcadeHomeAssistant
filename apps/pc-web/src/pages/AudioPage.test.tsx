import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../services/audioApi', () => ({
  getAudioState: vi.fn(),
  getAudioDevices: vi.fn(),
  setVolume: vi.fn(),
  setMute: vi.fn(),
  switchDevice: vi.fn(),
}));

import * as audioApi from '../services/audioApi';
import { useAgentStore } from '../stores/agentStore';
import { AudioPage } from './AudioPage';

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

describe('AudioPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Reset the agent store so the header always renders the default base URL.
    useAgentStore.setState({
      baseUrl: 'http://127.0.0.1:8765',
    });
  });

  it('renders header, slider, mute toggle, and devices with mocked query data', async () => {
    vi.mocked(audioApi.getAudioState).mockResolvedValue({
      masterVolume: 0.5,
      muted: false,
      defaultDeviceId: 'd1',
    });
    vi.mocked(audioApi.getAudioDevices).mockResolvedValue([
      { id: 'd1', name: 'Speakers', isDefault: true, state: 'active' },
      { id: 'd2', name: 'Headset', isDefault: false, state: 'active' },
    ]);

    renderWithProviders(<AudioPage />);

    // Header shows agent base URL.
    expect(screen.getByText(/127\.0\.0\.1:8765/)).toBeInTheDocument();

    // Volume slider is labelled in Chinese for the accessibility check.
    // Wait for the query to resolve so the slider's controlled value reflects
    // the cached server state instead of the loading-default zero.
    const slider = (await screen.findByLabelText('主音量')) as HTMLInputElement;
    expect(slider).toHaveAttribute('type', 'range');
    await waitFor(() => expect(slider).toHaveValue('50'));

    // Percentage display is visible.
    expect(screen.getByText('50%')).toBeInTheDocument();

    // Devices appear, with the default highlighted.
    await waitFor(() => {
      expect(screen.getByText('Speakers')).toBeInTheDocument();
      expect(screen.getByText('Headset')).toBeInTheDocument();
    });
  });

  it('triggers volume mutation on mouseUp (not on every change)', async () => {
    vi.mocked(audioApi.getAudioState).mockResolvedValue({
      masterVolume: 0.5,
      muted: false,
      defaultDeviceId: 'd1',
    });
    vi.mocked(audioApi.getAudioDevices).mockResolvedValue([]);
    vi.mocked(audioApi.setVolume).mockResolvedValue();

    renderWithProviders(<AudioPage />);

    const slider = (await screen.findByLabelText('主音量')) as HTMLInputElement;
    // Wait for query to resolve before driving the slider.
    await waitFor(() => expect(slider).toHaveValue('50'));

    // Multiple drag-style change events should NOT fire the mutation.
    fireEvent.change(slider, { target: { value: '60' } });
    fireEvent.change(slider, { target: { value: '70' } });
    fireEvent.change(slider, { target: { value: '80' } });
    expect(audioApi.setVolume).not.toHaveBeenCalled();

    // mouseUp commits the value — exactly one call, scaled to [0, 1].
    fireEvent.mouseUp(slider);
    await waitFor(() => {
      expect(audioApi.setVolume).toHaveBeenCalledTimes(1);
    });
    expect(audioApi.setVolume).toHaveBeenCalledWith(0.8);
  });

  it('shows an error message when a mutation fails', async () => {
    vi.mocked(audioApi.getAudioState).mockResolvedValue({
      masterVolume: 0.5,
      muted: false,
      defaultDeviceId: 'd1',
    });
    vi.mocked(audioApi.getAudioDevices).mockResolvedValue([]);
    vi.mocked(audioApi.setMute).mockRejectedValue(new Error('boom'));

    renderWithProviders(<AudioPage />);

    const muteButton = await screen.findByRole('button', { name: /静音/ });
    // Button is `disabled` until the state query resolves — wait for it to
    // become interactive before clicking, otherwise the click is a no-op.
    await waitFor(() => expect(muteButton).not.toBeDisabled());
    fireEvent.click(muteButton);

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument();
    });
    expect(screen.getByRole('alert').textContent).toMatch(/boom|失败/);
  });
});
