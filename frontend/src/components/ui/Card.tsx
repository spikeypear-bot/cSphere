import type { HTMLAttributes } from 'react'
import './ui.css'

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  const classes = ['card', className].filter(Boolean).join(' ')
  return <div className={classes} {...props} />
}
