import { agentApi } from './agentApi';

export interface AgentCapabilities {
  audioVolume?: boolean;
  audioMute?: boolean;
  audioDeviceSwitch?: boolean;
  fileManagement?: boolean;
  discoveryBroadcast?: boolean;
  remoteShutdown?: boolean;
}

export interface AgentStatus {
  machineName: string;
  version: string;
  uptimeSeconds: number;
  baseUrl?: string | null;
  capabilities: AgentCapabilities;
}

export interface RemoteShutdownStatus {
  available: boolean;
  state: 'idle' | 'executing' | 'failed' | string;
  error: string | null;
}

export async function getAgentStatus(): Promise<AgentStatus> {
  const response = await agentApi.get<AgentStatus>('/api/status');
  return response.data;
}

export async function getRemoteShutdownStatus(): Promise<RemoteShutdownStatus> {
  const response = await agentApi.get<RemoteShutdownStatus>('/api/power/shutdown');
  return response.data;
}

export async function executeRemoteShutdown(
  controlToken: string,
): Promise<RemoteShutdownStatus> {
  const response = await agentApi.post<RemoteShutdownStatus>(
    '/api/power/shutdown',
    { confirm: true },
    { headers: { Authorization: `Bearer ${controlToken}` } },
  );
  return response.data;
}
