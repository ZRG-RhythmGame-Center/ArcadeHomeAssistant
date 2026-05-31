import { create } from 'zustand';

export interface AgentState {
  /** Base URL of the Windows Agent, e.g. `http://127.0.0.1:8765`. */
  baseUrl: string;
  setBaseUrl: (baseUrl: string) => void;
}

const DEFAULT_BASE_URL = 'http://127.0.0.1:8765';

export const useAgentStore = create<AgentState>((set) => ({
  baseUrl: DEFAULT_BASE_URL,
  setBaseUrl: (baseUrl) => set({ baseUrl }),
}));
