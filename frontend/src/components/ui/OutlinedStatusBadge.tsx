import type { ComponentPropsWithoutRef } from 'react'
import './OutlinedStatusBadge.css'

type OutlinedStatusBadgeProps = ComponentPropsWithoutRef<'span'> & {
  tone: 'success' | 'warning' | 'danger' | 'info' | 'neutral'
}

/** A lightly tinted status badge with matching text and outline colours. */
export function OutlinedStatusBadge({ tone, className = '', ...props }: OutlinedStatusBadgeProps) {
  return <span {...props} className={`outlined-status-badge ${className}`.trim()} data-tone={tone} />
}
