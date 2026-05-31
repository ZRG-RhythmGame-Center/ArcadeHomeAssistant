import { agentApi } from './agentApi';

/**
 * Public-facing root descriptor — never includes the on-disk path.
 *
 * Mirrors the Agent's `FileRootDto` over the wire (camelCase).
 */
export interface FileRoot {
  id: string;
  name: string;
  readOnly: boolean;
}

/** Single directory entry returned by `/api/files`. */
export interface FileEntry {
  name: string;
  /** `"dir"` for subdirectories, `"file"` for everything else. */
  kind: 'dir' | 'file';
  /** Byte length for files. `null` for directories. */
  size: number | null;
  /** ISO-8601 string in UTC; the Agent serializes `DateTime.LastWriteTimeUtc`. */
  modified: string;
}

/** Paginated listing payload returned by `/api/files`. */
export interface FileListingResult {
  entries: FileEntry[];
  total: number;
  truncated: boolean;
}

/** Default `limit` when callers omit one. Mirrors Agent `FileListingEndpoints.DefaultLimit`. */
export const DEFAULT_LISTING_LIMIT = 200;

export async function getFileRoots(): Promise<FileRoot[]> {
  const response = await agentApi.get<FileRoot[]>('/api/file-roots');
  return response.data;
}

export async function getFileListing(
  rootId: string,
  path: string,
  limit?: number
): Promise<FileListingResult> {
  const response = await agentApi.get<FileListingResult>('/api/files', {
    params: {
      rootId,
      path,
      limit: limit ?? DEFAULT_LISTING_LIMIT,
    },
  });
  return response.data;
}

export interface UploadResult {
  rootId: string;
  path: string;
  size: number;
  overwritten: boolean;
}

export async function uploadFile(
  rootId: string,
  path: string,
  file: File,
  overwrite = false
): Promise<UploadResult> {
  const form = new FormData();
  form.append('rootId', rootId);
  form.append('path', path);
  form.append('overwrite', overwrite ? 'true' : 'false');
  form.append('file', file, file.name);
  const response = await agentApi.post<UploadResult>('/api/files/upload', form);
  return response.data;
}

export async function downloadFile(rootId: string, path: string): Promise<Blob> {
  const response = await agentApi.get<Blob>('/api/files/download', {
    params: { rootId, path },
    responseType: 'blob',
  });
  return response.data;
}

export interface MutationResult {
  rootId: string;
  path?: string;
  fromPath?: string;
  toPath?: string;
}

/**
 * Delete a single file under a root.
 *
 * `confirm: true` is wired in here automatically — UI components MUST NOT
 * surface this flag. The Agent rejects the request with `confirm_required`
 * if it ever leaks through as `false`/missing.
 */
export async function deleteFile(rootId: string, path: string): Promise<MutationResult> {
  const response = await agentApi.delete<MutationResult>('/api/files', {
    data: { rootId, path, confirm: true },
  });
  return response.data;
}

/** Rename a file within the same directory. Auto-sets `confirm:true`. */
export async function renameFile(
  rootId: string,
  path: string,
  newName: string
): Promise<MutationResult> {
  const response = await agentApi.post<MutationResult>('/api/files/rename', {
    rootId,
    path,
    newName,
    confirm: true,
  });
  return response.data;
}

/** Move a file within the same root. Auto-sets `confirm:true`. */
export async function moveFile(
  rootId: string,
  fromPath: string,
  toPath: string
): Promise<MutationResult> {
  const response = await agentApi.post<MutationResult>('/api/files/move', {
    rootId,
    fromPath,
    toPath,
    confirm: true,
  });
  return response.data;
}
