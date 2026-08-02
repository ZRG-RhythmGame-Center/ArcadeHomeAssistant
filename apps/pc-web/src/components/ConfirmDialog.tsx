import type { ReactNode } from 'react';

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = '确认',
  cancelLabel = '取消',
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  if (!open) return null;
  return (
    <div className="modal-overlay" role="dialog" aria-modal="true" aria-label={title}>
      <div className="modal-card">
        <h2 className="modal-title">{title}</h2>
        <div className="modal-body">{message}</div>
        <div className="modal-actions">
          <button type="button" className="modal-secondary" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button type="button" className="modal-danger" onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

interface PromptDialogProps {
  open: boolean;
  title: string;
  label: string;
  initialValue?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm: (value: string) => void;
  onCancel: () => void;
}

export function PromptDialog({
  open,
  title,
  label,
  initialValue = '',
  confirmLabel = '确认',
  cancelLabel = '取消',
  onConfirm,
  onCancel,
}: PromptDialogProps) {
  if (!open) return null;
  return (
    <PromptDialogInner
      title={title}
      label={label}
      initialValue={initialValue}
      confirmLabel={confirmLabel}
      cancelLabel={cancelLabel}
      onConfirm={onConfirm}
      onCancel={onCancel}
    />
  );
}

import { useState } from 'react';

function PromptDialogInner({
  title,
  label,
  initialValue,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
}: Omit<PromptDialogProps, 'open'>) {
  const [value, setValue] = useState(initialValue);
  return (
    <div className="modal-overlay" role="dialog" aria-modal="true" aria-label={title}>
      <div className="modal-card">
        <h2 className="modal-title">{title}</h2>
        <div className="modal-body">
          <label className="modal-label">
            {label}
            <input
              type="text"
              className="modal-input"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              autoFocus
            />
          </label>
        </div>
        <div className="modal-actions">
          <button type="button" className="modal-secondary" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button type="button" className="modal-danger" onClick={() => onConfirm(value ?? '')}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
