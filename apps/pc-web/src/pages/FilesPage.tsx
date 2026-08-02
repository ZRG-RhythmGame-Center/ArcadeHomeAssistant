import { useEffect, useMemo, useRef, useState, type ChangeEvent } from 'react';
import {
  useDelete,
  useDownload,
  useFileListing,
  useFileRoots,
  useLoadMore,
  useMove,
  useRename,
  useUpload,
} from '../hooks/useFiles';
import { ConfirmDialog, PromptDialog } from '../components/ConfirmDialog';
import type { FileEntry, FileRoot } from '../services/filesApi';

function joinPath(dir: string, name: string): string {
  if (!dir) {
    return name;
  }
  return `${dir.replace(/\/+$/, '')}/${name}`;
}

function splitPath(path: string): string[] {
  return path.split('/').filter(Boolean);
}

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
            下载
          </button>
        )}
        <button type="button" onClick={onRename} disabled={rootReadOnly || isDir}>
          重命名
        </button>
        <button type="button" onClick={onMove} disabled={rootReadOnly || isDir}>
          移动
        </button>
        <button
          type="button"
          onClick={onDelete}
          disabled={rootReadOnly || isDir}
          className="files-action--danger"
        >
          删除
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
  const loadMore = useLoadMore(selectedRootId, path);
  const upload = useUpload();
  const remove = useDelete();
  const rename = useRename();
  const move = useMove();
  const download = useDownload();

  const fileInputRef = useRef<HTMLInputElement>(null);

  const [deleteTarget, setDeleteTarget] = useState<FileEntry | null>(null);
  const [renameTarget, setRenameTarget] = useState<FileEntry | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [moveTarget, setMoveTarget] = useState<FileEntry | null>(null);
  const [moveValue, setMoveValue] = useState('');
  const [uploadConflict, setUploadConflict] = useState<{ name: string; file: File } | null>(null);

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
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : '';
      if (message.includes('409') || message.toLowerCase().includes('conflict') || message.includes('已存在')) {
        setUploadConflict({ name: file.name, file });
      }
    }
  }

  async function handleOverwriteConfirm() {
    if (!uploadConflict || !selectedRootId) return;
    const targetPath = joinPath(path, uploadConflict.name);
    try {
      await upload.mutateAsync({
        rootId: selectedRootId,
        path: targetPath,
        file: uploadConflict.file,
        overwrite: true,
      });
    } catch {
      // Error visible in mutation state.
    }
    setUploadConflict(null);
  }

  async function handleDelete(entry: FileEntry) {
    if (!selectedRootId) return;
    const targetPath = joinPath(path, entry.name);
    try {
      await remove.mutateAsync({ rootId: selectedRootId, path: targetPath });
    } catch {
      // Error visible in mutation state.
    }
    setDeleteTarget(null);
  }

  async function handleRename(entry: FileEntry, newName: string) {
    if (!selectedRootId || !newName || newName === entry.name) return;
    const targetPath = joinPath(path, entry.name);
    try {
      await rename.mutateAsync({ rootId: selectedRootId, path: targetPath, newName });
    } catch {
      // Error visible in mutation state.
    }
    setRenameTarget(null);
  }

  async function handleMove(entry: FileEntry, toPath: string) {
    if (!selectedRootId || !toPath || toPath === joinPath(path, entry.name)) return;
    const fromPath = joinPath(path, entry.name);
    try {
      await move.mutateAsync({ rootId: selectedRootId, fromPath, toPath });
    } catch {
      // Error visible in mutation state.
    }
    setMoveTarget(null);
  }

  async function handleDownload(entry: FileEntry) {
    if (!selectedRootId) return;
    const targetPath = joinPath(path, entry.name);
    try {
      await download({ rootId: selectedRootId, path: targetPath });
    } catch {
      // Failure to fetch a blob is surfaced through the browser.
    }
  }

  function handleLoadMore() {
    const currentCount = listing.data?.entries.length ?? 0;
    loadMore.mutate({ offset: currentCount });
  }

  const segments = splitPath(path);
  const writeDisabled = !selectedRoot || selectedRoot.readOnly;

  const mutationError =
    upload.error?.message ||
    remove.error?.message ||
    rename.error?.message ||
    move.error?.message ||
    null;

  return (
    <section className="files-page">
      <h1>文件</h1>

      <div className="files-layout">
        <aside className="files-sidebar" aria-label="File roots">
          <h2 className="files-sidebar-title">根目录</h2>
          {rootsQuery.isLoading && <p className="files-muted">加载中…</p>}
          {rootsQuery.isError && (
            <p className="files-error">加载失败: {rootsQuery.error?.message}</p>
          )}
          {!rootsQuery.isLoading && roots.length === 0 && (
            <p className="files-muted">未配置文件根目录。</p>
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
                    {root.readOnly && <span className="files-badge">只读</span>}
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
                  上传
                  <input
                    ref={fileInputRef}
                    type="file"
                    onChange={handleUpload}
                    disabled={writeDisabled || upload.isPending}
                  />
                </label>
                {upload.isPending && <span className="files-muted">上传中…</span>}
              </div>

              {listing.isLoading && <p className="files-muted">加载中…</p>}
              {listing.isError && (
                <p className="files-error">
                  加载失败: {listing.error?.message}
                </p>
              )}
              {listing.data?.truncated && (
                <p className="files-banner" role="status">
                  仅显示前 {listing.data.entries.length} / {listing.data.total} 项。
                  <button type="button" onClick={handleLoadMore} disabled={loadMore.isPending}>
                    {loadMore.isPending ? '加载中…' : '加载更多'}
                  </button>
                </p>
              )}

              {listing.data && (
                <table className="files-table">
                  <thead>
                    <tr>
                      <th>名称</th>
                      <th>类型</th>
                      <th className="files-cell--right">大小</th>
                      <th>修改时间</th>
                      <th className="files-cell--actions">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {listing.data.entries.length === 0 ? (
                      <tr>
                        <td colSpan={5} className="files-muted">
                          (空)
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
                          onDelete={() => setDeleteTarget(entry)}
                          onRename={() => {
                            setRenameTarget(entry);
                            setRenameValue(entry.name);
                          }}
                          onMove={() => {
                            setMoveTarget(entry);
                            setMoveValue(joinPath(path, entry.name));
                          }}
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
            <p className="files-muted">请选择一个根目录。</p>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={deleteTarget !== null}
        title="删除文件"
        message={`确认删除 ${deleteTarget ? joinPath(path, deleteTarget.name) : ''}？此操作不可撤销。`}
        confirmLabel="删除"
        cancelLabel="取消"
        onConfirm={() => deleteTarget && handleDelete(deleteTarget)}
        onCancel={() => setDeleteTarget(null)}
      />

      <PromptDialog
        open={renameTarget !== null}
        title="重命名"
        label="新名称"
        initialValue={renameValue}
        confirmLabel="重命名"
        cancelLabel="取消"
        onConfirm={(value) => renameTarget && handleRename(renameTarget, value)}
        onCancel={() => setRenameTarget(null)}
      />

      <PromptDialog
        open={moveTarget !== null}
        title="移动"
        label="目标路径（同一根目录内）"
        initialValue={moveValue}
        confirmLabel="移动"
        cancelLabel="取消"
        onConfirm={(value) => moveTarget && handleMove(moveTarget, value)}
        onCancel={() => setMoveTarget(null)}
      />

      <ConfirmDialog
        open={uploadConflict !== null}
        title="文件已存在"
        message={`文件 "${uploadConflict?.name ?? ''}" 已存在，是否覆盖？`}
        confirmLabel="覆盖"
        cancelLabel="取消"
        onConfirm={handleOverwriteConfirm}
        onCancel={() => setUploadConflict(null)}
      />
    </section>
  );
}
