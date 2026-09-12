import type { ReactNode } from 'react'
import './ui.css'

interface FieldShellProps {
  label: string
  hint?: string
  error?: string
  htmlFor: string
  children: ReactNode
}

function FieldShell({ label, hint, error, htmlFor, children }: FieldShellProps) {
  const classes = ['field', error ? 'field--invalid' : null].filter(Boolean).join(' ')
  return (
    <div className={classes}>
      <label htmlFor={htmlFor}>{label}</label>
      {children}
      {error ? (
        <span className="field-error" role="alert">
          {error}
        </span>
      ) : hint ? (
        <span className="field-hint">{hint}</span>
      ) : null}
    </div>
  )
}

interface TextFieldProps {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  placeholder?: string
  hint?: string
  error?: string
  multiline?: boolean
}

export function TextField({
  id,
  label,
  value,
  onChange,
  placeholder,
  hint,
  error,
  multiline,
}: TextFieldProps) {
  return (
    <FieldShell label={label} hint={hint} error={error} htmlFor={id}>
      {multiline ? (
        <textarea
          id={id}
          value={value}
          placeholder={placeholder}
          onChange={(event) => onChange(event.target.value)}
        />
      ) : (
        <input
          id={id}
          type="text"
          value={value}
          placeholder={placeholder}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </FieldShell>
  )
}

interface NumberFieldProps {
  id: string
  label: string
  value: number | null
  onChange: (value: number | null) => void
  hint?: string
  error?: string
  min?: number
}

export function NumberField({ id, label, value, onChange, hint, error, min }: NumberFieldProps) {
  return (
    <FieldShell label={label} hint={hint} error={error} htmlFor={id}>
      <input
        id={id}
        type="number"
        min={min}
        value={value ?? ''}
        onChange={(event) => {
          const raw = event.target.value
          onChange(raw === '' ? null : Number(raw))
        }}
      />
    </FieldShell>
  )
}

interface DateTimeFieldProps {
  id: string
  label: string
  value: string | null
  onChange: (isoValue: string | null) => void
  hint?: string
  error?: string
}

/** Stores/emits a full ISO-8601 instant (matching the backend's OffsetDateTime),
 * while the visible control is a plain `datetime-local` input (no timezone
 * picker — the browser's local zone is used, which is an acceptable
 * simplification for a first slice; see docs/decision-log.md if this needs
 * revisiting). */
export function DateTimeField({ id, label, value, onChange, hint, error }: DateTimeFieldProps) {
  const localValue = value ? toDatetimeLocalValue(value) : ''
  return (
    <FieldShell label={label} hint={hint} error={error} htmlFor={id}>
      <input
        id={id}
        type="datetime-local"
        value={localValue}
        onChange={(event) => {
          const raw = event.target.value
          onChange(raw === '' ? null : new Date(raw).toISOString())
        }}
      />
    </FieldShell>
  )
}

function toDatetimeLocalValue(iso: string): string {
  const date = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

interface CheckboxFieldProps {
  id: string
  label: string
  checked: boolean
  onChange: (checked: boolean) => void
  hint?: string
}

export function CheckboxField({ id, label, checked, onChange, hint }: CheckboxFieldProps) {
  return (
    <div className="field" style={{ flexDirection: 'row', alignItems: 'center', gap: '0.6rem' }}>
      <input
        id={id}
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        style={{ width: 'auto' }}
      />
      <label htmlFor={id} style={{ fontWeight: 500 }}>
        {label}
      </label>
      {hint ? <span className="field-hint">{hint}</span> : null}
    </div>
  )
}
