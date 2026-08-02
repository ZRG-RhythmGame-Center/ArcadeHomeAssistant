import { agentApi } from './agentApi';

export interface LauncherStatus {
  visible: boolean;
  hasActiveItem: boolean;
  activeItemId: string | null;
  activeItemName: string | null;
  state: string;
  lastError: string | null;
}

export async function getLauncherStatus(): Promise<LauncherStatus> {
  const response = await agentApi.get<LauncherStatus>('/api/launcher/status');
  return response.data;
}

export async function showLauncher(): Promise<LauncherStatus> {
  const response = await agentApi.post<LauncherStatus>('/api/launcher/show');
  return response.data;
}

export async function startLauncherItem(itemId: string): Promise<LauncherStatus> {
  const response = await agentApi.post<LauncherStatus>('/api/launcher/start', { itemId });
  return response.data;
}

export async function stopLauncherItem(): Promise<LauncherStatus> {
  const response = await agentApi.post<LauncherStatus>('/api/launcher/stop');
  return response.data;
}
