import { useId, type HTMLAttributes, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import './dashboard.css'

/** One row or card that opens a page (or, with `onClick` and no `to`, a
 * window such as a Modal): the whole surface is a single link or button,
 * with a trailing chevron. Its accessible name is the title alone (so a
 * screen reader hears "Gala dinner, link", not every figure); the badges, details and
 * aside are its description. */
export function RowLink({ to, onClick, title, titleAs: Title = 'span', media, badges, aside, children, className, ...rest }: {
  to?: string
  /** Without `to`: the row is a button that runs this. */
  onClick?: () => void
  title: ReactNode
  /** A heading level when the row heads its own content (list pages). */
  titleAs?: 'h2' | 'h3' | 'span'
  /** A photo or icon before the text. */
  media?: ReactNode
  /** Chips shown beside the title. */
  badges?: ReactNode
  /** A short value at the end of the row, before the chevron ("3 left"). */
  aside?: ReactNode
  /** Lines under the title. */
  children?: ReactNode
  className?: string
} & Omit<HTMLAttributes<HTMLElement>, 'title' | 'children' | 'className' | 'onClick'>) {
  const id = useId()
  const described = [badges ? `${id}-badges` : '', children ? `${id}-details` : '', aside ? `${id}-aside` : '']
    .filter(Boolean).join(' ')
  const shared = {
    ...rest,
    className: className ? `row-link ${className}` : 'row-link',
    'aria-labelledby': `${id}-title`,
    'aria-describedby': described || undefined,
  }
  const content = (
    <>
      {media ? <div className="row-link__media">{media}</div> : null}
      <div className="row-link__main">
        <div className="row-link__top">
          <Title id={`${id}-title`} className="row-link__title">{title}</Title>
          {badges ? <span id={`${id}-badges`} className="row-link__badges">{badges}</span> : null}
        </div>
        {children ? <div id={`${id}-details`} className="row-link__details">{children}</div> : null}
      </div>
      {aside ? <span id={`${id}-aside`} className="row-link__aside">{aside}</span> : null}
      <span className="row-link__chevron" aria-hidden="true">›</span>
    </>
  )
  return to === undefined
    ? <button type="button" onClick={onClick} {...shared}>{content}</button>
    : <Link to={to} {...shared}>{content}</Link>
}
