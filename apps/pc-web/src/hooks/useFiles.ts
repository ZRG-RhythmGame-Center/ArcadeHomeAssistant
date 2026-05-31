import { useCallback } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  deleteFile,
  downloadFile,
  getFileListing,
  getFileRoots,
  moveFile,
  renameFile,
  uploadFile,
  type FileListingResult,
  type FileRoot,
  type MutationResult,
  type UploadResult,
} from '../services/filesApi';

/**
 * Query keys used across files hooks. Centralized so invalidations stay
 * in lock-step with reads.
 *
 * The `listing` key is intentionally a *prefix*: the root id is included
 * but the path is not. That lets a mutation on any path under a given root
 * invalidate every cached subdirectory in one call instead of trying to
 * surgically pick the right subset.
 */
export const filesKeys = {
  all: ['files'] as const,
  roots: () => [...filesKeys.all, 'roots'] as const,
  listingByRoot: (rootId: string) => [...filesKeys.all, 'listing', rootId] as const,
  listing: (rootId: string, path: string) =>
    [...filesKeys.all, 'listing', rootId, path] as const,
};

/** List configured file roots. */
export function useFileRoots() {
  return useQuery<FileRoot[]>({
    queryKey: filesKeys.roots(),
    queryFn: getFileRoots,
  });
}

/**
 * List one directory level under a root. When `rootId` is null the query
 * stays disabled so we don't fire `/api/files` until the user picks a root.
 */
export function useFileListing(rootId: string | null, path: string) {
  return useQuery<FileListingResult>({
    queryKey: rootId
      ? filesKeys.listing(rootId, path)
      : ([...filesKeys.all, 'listing', '__none__'] as readonly unknown[]),
    queryFn: () => getFileListing(rootId as string, path),
    enabled: rootId !== null,
  });
}

export interface UploadVariables {
  rootId: string;
  path: string;
  file: File;
  overwrite?: boolean;
}

/** Upload a single file. Invalidates every cached listing under the affected root. */
export function useUpload() {
  const queryClient = useQueryClient();
  return useMutation<UploadResult, Error, UploadVariables>({
    mutationFn: ({ rootId, path, file, overwrite }) =>
      uploadFile(rootId, path, file, overwrite ?? false),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: filesKeys.listingByRoot(vars.rootId) });
    },
  });
}

export interface DeleteVariables {
  rootId: string;
  path: string;
}

export function useDelete() {
  const queryClient = useQueryClient();
  return useMutation<MutationResult, Error, DeleteVariables>({
    mutationFn: ({ rootId, path }) => deleteFile(rootId, path),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: filesKeys.listingByRoot(vars.rootId) });
    },
  });
}

export interface RenameVariables {
  rootId: string;
  path: string;
  newName: string;
}

export function useRename() {
  const queryClient = useQueryClient();
  return useMutation<MutationResult, Error, RenameVariables>({
    mutationFn: ({ rootId, path, newName }) => renameFile(rootId, path, newName),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: filesKeys.listingByRoot(vars.rootId) });
    },
  });
}

export interface MoveVariables {
  rootId: string;
  fromPath: string;
  toPath: string;
}

export function useMove() {
  const queryClient = useQueryClient();
  return useMutation<MutationResult, Error, MoveVariables>({
    mutationFn: ({ rootId, fromPath, toPath }) => moveFile(rootId, fromPath, toPath),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: filesKeys.listingByRoot(vars.rootId) });
    },
  });
}

export interface DownloadVariables {
  rootId: string;
  path: string;
}

/**
 * Returns a stable callback that fetches the file as a blob and triggers
 * a browser download via a temporary anchor click. Not a `useMutation`
 * because TanStack Query has nothing to cache — the side effect IS the
 * outcome.
 */
export function useDownload() {
  return useCallback(async ({ rootId, path }: DownloadVariables) => {
    const blob = await downloadFile(rootId, path);
    // Pull the basename so the download dialog defaults to the file's own name
    // (the path may contain forward slashes from the listing).
    const basename = path.split('/').pop() || 'download';
    const url = URL.createObjectURL(blob);
    try {
      const a = document.createElement('a');
      a.href = url;
      a.download = basename;
      a.click();
    } finally {
      URL.revokeObjectURL(url);
    }
  }, []);
}
