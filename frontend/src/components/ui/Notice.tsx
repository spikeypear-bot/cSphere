import type { ReactNode } from 'react'
import './layout.css'

interface NoticeProps {
  tone?: 'success' | 'info' | 'danger'
  as?: 'p' | 'div'
  children: ReactNode
}

/** A banner for the outcome of an action. Danger notices are announced as
 * alerts; the others as status updates. */
export function Notice({ tone = 'success', as: Tag = 'p', children }: NoticeProps) {
  return (
    <Tag className={`notice notice--${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      {children}
    </Tag>
  )
}
