import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getLauncherStatus,
  showLauncher,
  startLauncherItem,
  stopLauncherItem,
  type LauncherStatus,
} from '../services/launcherApi';

export const launcherKeys = {
  all: ['launcher'] as const,
  status: ['launcher', 'status'] as const,
};

export function useLauncherStatus() {
  return useQuery<LauncherStatus>({
    queryKey: launcherKeys.status,
    queryFn: getLauncherStatus,
  });
}

export function useShowLauncher() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: showLauncher,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: launcherKeys.status });
    },
  });
}

export function useStartLauncherItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (itemId: string) => startLauncherItem(itemId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: launcherKeys.status });
    },
  });
}

export function useStopLauncherItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: stopLauncherItem,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: launcherKeys.status });
    },
  });
}
