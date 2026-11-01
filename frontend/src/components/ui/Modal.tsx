import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import './modal.css'

/** A modal window on the browser's own <dialog>: focus stays inside, Escape
 * and the Close button both close it, and `onClose` runs once either way. */
export function Modal({ title, onClose, footer, children }: {
  title: string
  onClose: () => void
  /** Actions along the bottom, e.g. "Show on map". As a function it gets
   * `close`: call it after an action so focus returns to the opener. */
  footer?: ReactNode | ((close: () => void) => ReactNode)
  children: ReactNode
}) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  // Every way out goes through the dialog's own close(), which returns focus to the opener.
  const [closing, setClosing] = useState(false)
  const close = () => setClosing(true)
  useEffect(() => {
    const dialog = ref.current
    if (dialog && !dialog.open) dialog.showModal()
  }, [])
  useEffect(() => {
    if (closing) ref.current?.close()
  }, [closing])
  return (
    <dialog ref={ref} className="modal" aria-labelledby={titleId} onClose={onClose}>
      <div className="modal__head">
        <h2 id={titleId}>{title}</h2>
        <button type="button" className="modal__close" aria-label="Close" onClick={close}>
          <span aria-hidden="true">✕</span>
        </button>
      </div>
      <div className="modal__body">{children}</div>
      {footer ? <div className="modal__foot">{typeof footer === 'function' ? footer(close) : footer}</div> : null}
    </dialog>
  )
}
