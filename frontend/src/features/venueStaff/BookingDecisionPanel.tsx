import type { HTMLAttributes } from 'react'

/** Shared surface for booking decisions and their saved details. */
export function BookingDecisionPanel({ as: Element = 'div', className = '', ...props }: HTMLAttributes<HTMLElement> & {
  as?: 'div' | 'form'
}) {
  return <Element className={`booking-approval__confirmation ${className}`.trim()} {...props} />
}
