import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import {
  useDelete,
  useDownload,
  useFileListing,
  useFileRoots,
  useMove,
  useRename,
  useUpload,
} from '../hooks/useFiles';
import type { FileEntry, FileRoot } from '../services/filesApi';

/** Join a directory path and a single name with `/`, dropping leading/trailing slashes. */
function joinPath(dir: string, name: string): string {
  if (!dir) {
    return name;
  }
  return `${dir.replace(/\/+$/, '')}/${name}`;
}

/** Split `a/b/c` into `['a', 'b', 'c']`, ignoring empty segments. */
function splitPath(path: string): string[] {
  return path.split('/').filter(Boolean);
}

/** Format `bytes` for the size column. Returns `''` for null (directories). */
function formatSize(size: number | null): string {
  if (size === null) {
    return '';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

/** Format an ISO-8601 string as a locale-aware short timestamp. */
function formatModified(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return iso;
  }
  return d.toLocaleString();
}

interface RowProps {
  entry: FileEntry;
  rootReadOnly: boolean;
  onOpen: () => void;
  onDownload: () => void;
  onDelete: () => void;
  onRename: () => void;
  onMove: () => void;
}

function FileRow({ entry, rootReadOnly, onOpen, onDownload, onDelete, onRename, onMove }: RowProps) {
  const isDir = entry.kind === 'dir';
  return (
    <tr aria-label={entry.name}>
      <td>
        {isDir ? (
          <button type="button" className="files-name-button" onClick={onOpen}>
            <span className="files-icon" aria-hidden>
              📁
            </span>
            {entry.name}
          </button>
        ) : (
          <span className="files-name">
            <span className="files-icon" aria-hidden>
              📄
            </span>
            {entry.name}
          </span>
        )}
      </td>
      <td>{isDir ? 'dir' : 'file'}</td>
      <td className="files-cell--right">{formatSize(entry.size)}</td>
      <td>{formatModified(entry.modified)}</td>
      <td className="files-cell--actions">
        {!isDir && (
          <button type="button" onClick={onDownload}>
            Download
          </button>
        )}
        <button type="button" onClick={onRename} disabled={rootReadOnly || isDir}>
          Rename
        </button>
        <button type="button" onClick={onMove} disabled={rootReadOnly || isDir}>
          Move
        </button>
        <button
          type="button"
          onClick={onDelete}
          disabled={rootReadOnly || isDir}
          className="files-action--danger"
        >
          Delete
        </button>
      </td>
    </tr>
  );
}

export function FilesPage() {
  const rootsQuery = useFileRoots();
  const roots = rootsQuery.data ?? [];

  const [selectedRootId, setSelectedRootId] = useState<string | null>(null);
  const [path, setPath] = useState('');

  // Auto-select the first root once roots load. Re-runs only when the set of
  // roots changes (length is a cheap proxy that holds for our use).
  useEffect(() => {
    if (selectedRootId === null && roots.length > 0) {
      setSelectedRootId(roots[0].id);
    }
  }, [roots, selectedRootId]);

  const selectedRoot: FileRoot | undefined = useMemo(
    () => roots.find((r) => r.id === selectedRootId),
    [roots, selectedRootId],
  );

  const listing = useFileListing(selectedRootId, path);
  const upload = useUpload();
  const remove = useDelete();
  const rename = useRename();
  const move = useMove();
  const download = useDownload();

  const fileInputRef = useRef<HTMLInputElement>(null);

  function selectRoot(rootId: string) {
    setSelectedRootId(rootId);
    setPath('');
  }

  function openDir(name: string) {
    setPath((current) => joinPath(current, name));
  }

  function navigateToSegment(segmentIndex: number) {
    const segments = splitPath(path);
    setPath(segments.slice(0, segmentIndex + 1).join('/'));
  }

  async function handleUpload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    // Always reset the input so the user can re-pick the same file.
    if (event.target) {
      event.target.value = '';
    }
    if (!file || !selectedRootId) {
      return;
    }
    const targetPath = joinPath(path, file.name);
    try {
      await upload.mutateAsync({
        rootId: selectedRootId,
        path: targetPath,
        file,
        overwrite: false,
      });
    } catch {
      // Surface failures via the upload mutation's error state below.
    }
  }

  async function handleDelete(entry: FileEntry) {
    if (!selectedRootId) {
      return;
    }
    const targetPath = joinPath(path, entry.name);
    if (!window.confirm(`Delete ${targetPath}? This cannot be undone.`)) {
      return;
    }
    try {
      await remove.mutateAsync({ rootId: selectedRootId, path: targetPath });
    } catch {
      // Error visible in mutation state below.
    }
  }

  async function handleRename(entry: FileEntry) {
    if (!selectedRootId) {
      return;
    }
    const newName = window.prompt(`Rename ${entry.name} to:`, entry.name);
    if (!newName || newName === entry.name) {
      return;
    }
    if (!window.confirm(`Rename ${entry.name} to ${newName}?`)) {
      return;
    }
    const targetPath = joinPath(path, entry.name);
    try {
      await rename.mutateAsync({ rootId: selectedRootId, path: targetPath, newName });
    } catch {
      // Error visible in mutation state below.
    }
  }

  async function handleMove(entry: FileEntry) {
    if (!selectedRootId) {
      return;
    }
    const fromPath = joinPath(path, entry.name);
    const toPath = window.prompt(
      `Move ${fromPath} to (path within the same root):`,
      fromPath,
    );
    if (!toPath || toPath === fromPath) {
      return;
    }
    if (!window.confirm(`Move ${fromPath} to ${toPath}?`)) {
      return;
    }
    try {
      await move.mutateAsync({ rootId: selectedRootId, fromPath, toPath });
    } catch {
      // Error visible in mutation state below.
    }
  }

  async function handleDownload(entry: FileEntry) {
    if (!selectedRootId) {
      return;
    }
    const targetPath = joinPath(path, entry.name);
    try {
      await download({ rootId: selectedRootId, path: targetPath });
    } catch {
      // Failure to fetch a blob is surfaced through the browser, not here.
    }
  }

  const segments = splitPath(path);
  const writeDisabled = !selectedRoot || selectedRoot.readOnly;

  // Combine mutation errors for a single inline status row at the bottom.
  const mutationError =
    upload.error?.message ||
    remove.error?.message ||
    rename.error?.message ||
    move.error?.message ||
    null;

  return (
    <section className="files-page">
      <h1>Files</h1>

      <div className="files-layout">
        <aside className="files-sidebar" aria-label="File roots">
          <h2 className="files-sidebar-title">Roots</h2>
          {rootsQuery.isLoading && <p className="files-muted">Loading roots…</p>}
          {rootsQuery.isError && (
            <p className="files-error">Failed to load roots: {rootsQuery.error?.message}</p>
          )}
          {!rootsQuery.isLoading && roots.length === 0 && (
            <p className="files-muted">No roots configured.</p>
          )}
          <ul className="files-root-list">
            {roots.map((root) => {
              const active = root.id === selectedRootId;
              return (
                <li key={root.id}>
                  <button
                    type="button"
                    className={
                      active ? 'files-root-button files-root-button--active' : 'files-root-button'
                    }
                    onClick={() => selectRoot(root.id)}
                    aria-pressed={active}
                  >
                    <span className="files-root-name">{root.name}</span>
                    {root.readOnly && <span className="files-badge">read-only</span>}
                  </button>
                </li>
              );
            })}
          </ul>
        </aside>

        <div className="files-main">
          {selectedRoot ? (
            <>
              <nav className="files-breadcrumb" aria-label="Breadcrumb">
                <button
                  type="button"
                  className="files-breadcrumb-link"
                  onClick={() => setPath('')}
                >
                  {selectedRoot.name}
                </button>
                {segments.map((segment, idx) => (
                  <span key={`${segment}-${idx}`} className="files-breadcrumb-segment">
                    <span className="files-breadcrumb-sep" aria-hidden>
                      /
                    </span>
                    <button
                      type="button"
                      className="files-breadcrumb-link"
                      onClick={() => navigateToSegment(idx)}
                    >
                      {segment}
                    </button>
                  </span>
                ))}
              </nav>

              <div className="files-toolbar">
                <label className="files-upload-label">
                  Upload
                  <input
                    ref={fileInputRef}
                    type="file"
                    onChange={handleUpload}
                    disabled={writeDisabled || upload.isPending}
                  />
                </label>
                {upload.isPending && <span className="files-muted">Uploading…</span>}
              </div>

              {listing.isLoading && <p className="files-muted">Loading…</p>}
              {listing.isError && (
                <p className="files-error">
                  Failed to load listing: {listing.error?.message}
                </p>
              )}
              {listing.data?.truncated && (
                <p className="files-banner" role="status">
                  Listing truncated — only the first {listing.data.entries.length} of{' '}
                  {listing.data.total} entries shown.
                </p>
              )}

              {listing.data && (
                <table className="files-table">
                  <thead>
                    <tr>
                      <th>Name</th>
                      <th>Kind</th>
                      <th className="files-cell--right">Size</th>
                      <th>Modified</th>
                      <th className="files-cell--actions">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {listing.data.entries.length === 0 ? (
                      <tr>
                        <td colSpan={5} className="files-muted">
                          (empty)
                        </td>
                      </tr>
                    ) : (
                      listing.data.entries.map((entry) => (
                        <FileRow
                          key={entry.name}
                          entry={entry}
                          rootReadOnly={selectedRoot.readOnly}
                          onOpen={() => openDir(entry.name)}
                          onDownload={() => handleDownload(entry)}
                          onDelete={() => handleDelete(entry)}
                          onRename={() => handleRename(entry)}
                          onMove={() => handleMove(entry)}
                        />
                      ))
                    )}
                  </tbody>
                </table>
              )}

              {mutationError && (
                <p className="files-error" role="alert">
                  {mutationError}
                </p>
              )}
            </>
          ) : (
            <p className="files-muted">Select a root to begin.</p>
          )}
        </div>
      </div>
    </section>
  );
}
