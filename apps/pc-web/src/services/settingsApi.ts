import { agentApi } from './agentApi';

export interface LauncherItemSettings {
  id: string;
  name: string;
  title: string;
  note: string | null;
  iconPath: string | null;
  commandLine: string;
  workingDirectory: string | null;
  stopCommandLine: string;
  stopWorkingDirectory: string | null;
  key: string;
  order: number;
  enabled: boolean;
}

export interface LauncherSettings {
  showOnAgentStart: boolean;
  showDelaySeconds: number;
  canvasWidth: number;
  canvasHeight: number;
  backgroundImagePath: string | null;
  navigateLeftKey: string;
  navigateRightKey: string;
  confirmKey: string;
  stopKey: string;
  items: LauncherItemSettings[];
}

export interface FileRootSettings {
  id: string;
  name: string;
  path: string;
  readOnly: boolean;
}

export interface RemoteShutdownSettings {
  enabled: boolean;
}

export interface AgentSettingsSnapshot {
  autoStartEnabled: boolean;
  launcher: LauncherSettings;
  fileRoots: FileRootSettings[];
  remoteShutdown: RemoteShutdownSettings;
}

export interface AgentSettingsUpdateRequest {
  autoStartEnabled?: boolean;
  launcher?: LauncherSettings;
  fileRoots?: FileRootSettings[];
  remoteShutdown?: RemoteShutdownSettings;
}

export async function getSettings(): Promise<AgentSettingsSnapshot> {
  const response = await agentApi.get<AgentSettingsSnapshot>('/api/settings');
  return response.data;
}

export async function updateSettings(
  request: AgentSettingsUpdateRequest,
): Promise<AgentSettingsSnapshot> {
  const response = await agentApi.put<AgentSettingsSnapshot>('/api/settings', request);
  return response.data;
}
