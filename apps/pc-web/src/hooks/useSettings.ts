import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getSettings,
  updateSettings,
  type AgentSettingsSnapshot,
  type AgentSettingsUpdateRequest,
} from '../services/settingsApi';

export const settingsKeys = {
  all: ['settings'] as const,
  snapshot: ['settings', 'snapshot'] as const,
};

export function useSettings() {
  return useQuery<AgentSettingsSnapshot>({
    queryKey: settingsKeys.snapshot,
    queryFn: getSettings,
  });
}

export function useUpdateSettings() {
  const queryClient = useQueryClient();
  return useMutation<AgentSettingsSnapshot, Error, AgentSettingsUpdateRequest>({
    mutationFn: (request) => updateSettings(request),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: settingsKeys.all });
    },
  });
}
