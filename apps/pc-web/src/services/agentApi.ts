import axios, {
  type AxiosInstance,
  type InternalAxiosRequestConfig,
} from 'axios';
import { useAgentStore } from '../stores/agentStore';

/**
 * Axios instance for talking to the Windows Agent.
 *
 * Reads `baseUrl` from the Zustand store at request time so LAN reconnection
 * (changing which agent we're targeting) takes effect without recreating the
 * instance. No auth headers — this app is LAN-only and the agent accepts
 * anonymous requests from any device on the same network.
 */
export const agentApi: AxiosInstance = axios.create({
  timeout: 5_000,
});

agentApi.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const { baseUrl } = useAgentStore.getState();
  config.baseURL = baseUrl;
  return config;
});
