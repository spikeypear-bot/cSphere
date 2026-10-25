import { useId, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import '../technicalSupport.css'

/** One row or card that opens a page: the whole surface is a single link,
 * with a trailing chevron. Its accessible name is the title alone (so a
 * screen reader hears "Gala dinner, link", not every figure); the badges, details and
 * aside are its description. */
export function RowLink({ to, title, titleAs: Title = 'span', media, badges, aside, children, className }: {
  to: string
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
}) {
  const id = useId()
  const described = [badges ? `${id}-badges` : '', children ? `${id}-details` : '', aside ? `${id}-aside` : '']
    .filter(Boolean).join(' ')
  return (
    <Link
      to={to}
      className={className ? `row-link ${className}` : 'row-link'}
      aria-labelledby={`${id}-title`}
      aria-describedby={described || undefined}
    >
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
    </Link>
  )
}
