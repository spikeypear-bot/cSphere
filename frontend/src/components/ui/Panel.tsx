import { useId, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import './dashboard.css'

/** A titled block of a role dashboard with a quiet "View all" link. */
export function Panel({ title, action, actionName, to, className, children }: {
  title: string
  /** The visible action text, e.g. "View all". */
  action: string
  /** The action's full name for assistive technology. */
  actionName: string
  to: string
  className?: string
  children: ReactNode
}) {
  const id = useId()
  return (
    <section className={className ? `card dash-panel ${className}` : 'card dash-panel'} aria-labelledby={id}>
      <div className="dash-panel__head">
        <h2 id={id}>{title}</h2>
        <Link className="button button--ghost dash-panel__action" to={to} aria-label={actionName}>
          {action} <span aria-hidden="true">→</span>
        </Link>
      </div>
      <div className="dash-panel__body">{children}</div>
    </section>
  )
}
