import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  executeRemoteShutdown,
  getAgentStatus,
  getRemoteShutdownStatus,
  type AgentStatus,
  type RemoteShutdownStatus,
} from '../services/powerApi';

export const powerQueryKeys = {
  all: ['power'] as const,
  agentStatus: ['power', 'agent-status'] as const,
  shutdown: ['power', 'shutdown'] as const,
};

export function useAgentStatus() {
  return useQuery<AgentStatus>({
    queryKey: powerQueryKeys.agentStatus,
    queryFn: getAgentStatus,
    staleTime: 5_000,
  });
}

export function useRemoteShutdownStatus() {
  return useQuery<RemoteShutdownStatus>({
    queryKey: powerQueryKeys.shutdown,
    queryFn: getRemoteShutdownStatus,
  });
}

export function useExecuteRemoteShutdown() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (controlToken: string) => executeRemoteShutdown(controlToken),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: powerQueryKeys.shutdown });
      queryClient.invalidateQueries({ queryKey: powerQueryKeys.agentStatus });
    },
  });
}
