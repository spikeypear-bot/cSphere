import type { ReactNode } from 'react'
import './layout.css'

interface NoticeProps {
  tone?: 'success' | 'info' | 'danger'
  children: ReactNode
}

/** A banner for the outcome of an action. Danger notices are announced as
 * alerts; the others as status updates. */
export function Notice({ tone = 'success', children }: NoticeProps) {
  return (
    <p className={`notice notice--${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      {children}
    </p>
  )
}
