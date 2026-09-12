import './ui.css'

export type AutosaveState = 'idle' | 'saving' | 'saved' | 'error'

const COPY: Record<AutosaveState, string> = {
  idle: 'Not saved yet',
  saving: 'Saving draft…',
  saved: 'Saved as draft',
  error: 'Could not save — your last saved version is safe',
}

/** EO01's "does not overwrite the last successfully saved version if a later
 * save attempt fails" is a backend guarantee (EventRequestService is
 * transactional) — this is the UI half: tell the person plainly that a failed
 * save didn't lose anything, matching the 2026 autosave-feedback pattern this
 * design follows (see docs/product-context.md). */
export function AutosaveIndicator({ state }: { state: AutosaveState }) {
  if (state === 'idle') return null
  return (
    <span className="autosave-indicator" data-state={state} role="status">
      <span className="dot" aria-hidden="true" />
      {COPY[state]}
    </span>
  )
}
