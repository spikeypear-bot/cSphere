import type { ReactNode } from 'react'
import './ui.css'

interface FieldShellProps {
  label: string
  hint?: string
  error?: string
  htmlFor: string
  required?: boolean
  children: ReactNode
}

function FieldShell({ label, hint, error, htmlFor, required, children }: FieldShellProps) {
  const classes = ['field', error ? 'field--invalid' : null].filter(Boolean).join(' ')
  return (
    <div className={classes}>
      <label htmlFor={htmlFor} className={required ? 'field-label--required' : undefined}>{label}</label>
      {children}
      {error ? (
        <span id={`${htmlFor}-error`} className="field-error" role="alert">
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
  required?: boolean
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
  required,
  multiline,
}: TextFieldProps) {
  return (
    <FieldShell label={label} hint={hint} error={error} htmlFor={id} required={required}>
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
  required?: boolean
  /** EO-C2: only whole numbers can be entered (no decimal point, sign or
   * exponent), e.g. a number of people. Zero is still enterable so the
   * form can say why it is not allowed. */
  wholeNumber?: boolean
}

const NOT_WHOLE_NUMBER_KEYS = new Set(['.', ',', 'e', 'E', '+', '-'])

export function NumberField({ id, label, value, onChange, hint, error, min, required, wholeNumber = false }: NumberFieldProps) {
  return (
    <FieldShell label={label} hint={hint} error={error} htmlFor={id} required={required}>
      <input
        id={id}
        type="number"
        min={min}
        step={wholeNumber ? 1 : undefined}
        inputMode={wholeNumber ? 'numeric' : undefined}
        aria-invalid={error ? true : undefined}
        value={value ?? ''}
        onKeyDown={(event) => {
          if (wholeNumber && NOT_WHOLE_NUMBER_KEYS.has(event.key)) event.preventDefault()
        }}
        onChange={(event) => {
          const raw = event.target.value
          // A pasted decimal or negative is refused rather than rounded.
          if (wholeNumber && raw !== '' && !/^\d+$/.test(raw)) return
          onChange(raw === '' ? null : Number(raw))
        }}
      />
    </FieldShell>
  )
}

interface DateTimeFieldProps {
  min?: string
  max?: string
  id: string
  label: string
  value: string | null
  onChange: (isoValue: string | null) => void
  hint?: string
  error?: string
  required?: boolean
}

/** Stores/emits a full ISO-8601 instant (matching the backend's OffsetDateTime),
 * while the visible control is a plain `datetime-local` input. EO-C2: what is
 * typed and shown is always Singapore time, whatever zone the browser is in,
 * so "10:00" means the same moment to the organiser, the coordinator and the
 * venue (Singapore has no daylight saving: always GMT+8). */
export function DateTimeField({ id, label, value, onChange, hint, error, min, max, required }: DateTimeFieldProps) {
  const localValue = value ? toDatetimeLocalValue(value) : ''
  return (
    <FieldShell label={label} hint={hint ?? SINGAPORE_TIME_HINT} error={error} htmlFor={id} required={required}>
      <input
        id={id}
        type="datetime-local"
        min={min ? toDatetimeLocalValue(min) : undefined}
        max={max ? toDatetimeLocalValue(max) : undefined}
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined}
        value={localValue}
        onChange={(event) => {
          const raw = event.target.value
          onChange(raw === '' ? null : fromSingaporeLocalValue(raw))
        }}
      />
    </FieldShell>
  )
}

export const SINGAPORE_TIME_HINT = 'Singapore time (GMT+8)'
const SINGAPORE_OFFSET_MS = 8 * 60 * 60 * 1000

/** An instant as Singapore wall-clock time, "YYYY-MM-DDTHH:mm". */
function toDatetimeLocalValue(iso: string): string {
  const time = Date.parse(iso)
  if (!Number.isFinite(time)) return ''
  return new Date(time + SINGAPORE_OFFSET_MS).toISOString().slice(0, 16)
}

/** "YYYY-MM-DDTHH:mm" read as Singapore time, as an ISO instant. */
function fromSingaporeLocalValue(local: string): string {
  return new Date(`${local}:00+08:00`).toISOString()
}

interface CheckboxFieldProps {
  id: string
  label: string
  checked: boolean
  onChange: (checked: boolean) => void
  hint?: string
  /** Larger, boxed treatment for a choice that drives the rest of the form. */
  prominent?: boolean
}

export function CheckboxField({ id, label, checked, onChange, hint, prominent = false }: CheckboxFieldProps) {
  return (
    <div
      className={prominent ? 'field field--prominent-check' : 'field'}
      style={prominent ? undefined : { flexDirection: 'row', alignItems: 'center', gap: '0.6rem' }}
    >
      <input
        id={id}
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        style={prominent ? undefined : { width: 'auto' }}
      />
      <label htmlFor={id} style={prominent ? undefined : { fontWeight: 500 }}>
        {label}
      </label>
      {hint ? <span className="field-hint">{hint}</span> : null}
    </div>
  )
}
