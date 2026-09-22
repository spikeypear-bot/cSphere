import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useNotifications } from './useNotifications'
import type { NotificationDto } from '../../types/notification'
import './notifications.css'

/** Relative time, same coarse style as EO01/EO15's "edited X ago"
 * (frontend/src/lib/relativeTime.ts) — kept local rather than imported to
 * avoid coupling this feature to the organiser one for a five-line helper. */
function timeAgo(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime()
  const minutes = Math.round(diffMs / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.round(hours / 24)}d ago`
}

function describe(n: NotificationDto): string {
  if (n.type === 'coordinator_assignment') {
    const verb = n.isReassignment ? 'reassigned to' : 'assigned to'
    return `"${n.eventName}" ${verb} ${n.coordinatorName ?? 'a coordinator'}`
  }
  const statusLabel = n.newStatus ? n.newStatus.charAt(0).toUpperCase() + n.newStatus.slice(1) : 'updated'
  return `"${n.eventName}" is now ${statusLabel}`
}

/** EO09/EO19: unread-count badge + a dropdown of every notification for the
 * signed-in user, newest first. Rendered in AppShell for any signed-in role
 * — today only Organisers receive anything, but nothing here assumes that. */
export function NotificationBell() {
  const { notifications, unreadCount, markRead } = useNotifications()
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function onClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', onClickOutside)
    return () => document.removeEventListener('mousedown', onClickOutside)
  }, [open])

  function handleSelect(n: NotificationDto) {
    if (!n.read) void markRead(n.notificationId)
    setOpen(false)
    if (n.linkPath) navigate(n.linkPath)
  }

  return (
    <div className="notification-bell" ref={containerRef}>
      <button
        type="button"
        className="notification-bell__trigger"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications'}
      >
        <span aria-hidden="true">🔔</span>
        {unreadCount > 0 ? <span className="notification-bell__badge">{unreadCount}</span> : null}
      </button>

      {open ? (
        <div className="notification-bell__panel" role="menu">
          <div className="notification-bell__panel-header">
            <h2>Notifications</h2>
          </div>
          {notifications.length === 0 ? (
            <p className="notification-bell__empty">Nothing yet.</p>
          ) : (
            <ul className="notification-bell__list">
              {notifications.map((n) => (
                <li key={n.notificationId}>
                  <button
                    type="button"
                    className="notification-bell__item"
                    data-read={n.read}
                    onClick={() => handleSelect(n)}
                  >
                    <span className="notification-bell__dot" aria-hidden="true" />
                    <span className="notification-bell__item-body">
                      <span className="notification-bell__item-text">{describe(n)}</span>
                      {n.type === 'status_change' && n.newStatus === 'rejected' && n.reason ? (
                        <span className="notification-bell__item-reason">Reason: {n.reason}</span>
                      ) : null}
                      <span className="notification-bell__item-time">{timeAgo(n.occurredAt)}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </div>
  )
}
