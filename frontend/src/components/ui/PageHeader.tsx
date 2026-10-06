import type { ReactNode } from 'react'
import './layout.css'

interface PageHeaderProps {
  title: ReactNode
  eyebrow?: ReactNode
  description?: ReactNode
  actions?: ReactNode
}

/** The title row at the top of a page: heading and supporting text on the
 * left, actions on the right, wrapping underneath on narrow screens. */
export function PageHeader({ title, eyebrow, description, actions }: PageHeaderProps) {
  return (
    <header className="page-header">
      <div className="page-header__text">
        {eyebrow ? <p className="field-hint">{eyebrow}</p> : null}
        <h1>{title}</h1>
        {typeof description === 'string'
          ? <p className="page-header__description">{description}</p>
          : description}
      </div>
      {actions ? <div className="page-header__actions">{actions}</div> : null}
    </header>
  )
}
