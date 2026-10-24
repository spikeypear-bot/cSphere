import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useNotifications } from './useNotifications'
import type { NotificationDto } from '../../types/notification'
import { JOURNEY_LABELS } from '../../types/status'
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
  if (n.type === 'venue_hold_expired') return `Tentative venue hold expired for "${n.eventName}"`
  if (n.type === 'venue_hold_expiring') return `Tentative venue hold expiring soon for "${n.eventName}"`
  if (n.type === 'event_reassignment_requested') return `Reassignment requested for "${n.eventName}"`
  if (n.type === 'event_reassignment_approved') {
    return n.message ?? `"${n.eventName}" has been reassigned`
  }
  if (n.type === 'event_reassignment_declined') {
    return n.message ?? `Reassignment declined for "${n.eventName}"`
  }
  if (n.type === 'change_request_submitted') return `Change requested for "${n.eventName}"`
  if (n.type === 'change_request_withdrawn') return `Change request withdrawn for "${n.eventName}"`
  if (n.type === 'change_request_decided') return n.message ?? `Your change request for "${n.eventName}" was decided`
  if (n.type === 'request_cancelled') return `The organiser cancelled the request "${n.eventName}"`
  if (n.type === 'event_cancellation_requested') return `Cancellation requested for "${n.eventName}"`
  if (n.type === 'event_cancellation_approved') return `"${n.eventName}" has been cancelled`
  if (n.type === 'event_cancellation_declined') return `Cancellation of "${n.eventName}" was declined`
  if (n.type === 'event_cancelled') return `"${n.eventName}" has been cancelled`
  if (n.type === 'equipment_request_submitted') return `New equipment request for "${n.eventName}"`
  if (n.type === 'equipment_change_review') return `An approved change to "${n.eventName}" affects its equipment`
  if (n.type === 'venue_timing_changed') return `Venue timings updated for "${n.eventName}"`
  if (n.type === 'venue_timing_conflict') return `Venue timing conflict for "${n.eventName}"`
  if (n.type === 'venue_unavailable') return `A venue booking for "${n.eventName}" is unavailable`
  if (n.type === 'event_confirmed') return `"${n.eventName}" is confirmed`
  if (n.type === 'event_returned_to_planning') return `"${n.eventName}" is back in Planning`
  if (n.type === 'event_safety_approved') return `"${n.eventName}" passed its safety check and is ready to confirm`
  if (n.type === 'clarification_requested') return `Clarification needed on "${n.eventName}"`
  if (n.type === 'clarification_responded') return `"${n.eventName}" was updated and resubmitted`
  if (n.type === 'venue_booking_requested') return `New venue booking request for "${n.eventName}"`
  if (n.type === 'venue_booking_cancelled') return `Venue booking request for "${n.eventName}" was cancelled`
  if (n.type === 'coordinator_assignment') {
    const verb = n.isReassignment ? 'reassigned to' : 'assigned to'
    return `"${n.eventName}" ${verb} ${n.coordinatorName ?? 'a coordinator'}`
  }
  const statusLabel = n.newStatus ? STATUS_CHANGE_LABELS[n.newStatus] ?? n.newStatus : 'updated'
  return `"${n.eventName}" is now ${statusLabel}`
}

/** EC-NEW2: the stored status a notification carries, in the shared status
 * vocabulary. An approved request is an event in Planning; "Approved" is not
 * one of the statuses any screen shows. */
const STATUS_CHANGE_LABELS: Record<string, string> = {
  approved: `in ${JOURNEY_LABELS.PLANNING}`,
  confirmed: JOURNEY_LABELS.CONFIRMED,
  rejected: JOURNEY_LABELS.REJECTED,
  cancelled: JOURNEY_LABELS.CANCELLED,
  clarification_required: JOURNEY_LABELS.CLARIFICATION_REQUIRED,
}

/** Messages the server writes as a full sentence, shown without quotes. */
const PLAIN_MESSAGE_TYPES = new Set<NotificationDto['type']>([
  'venue_unavailable',
  'venue_hold_expiring',
  'equipment_request_submitted',
  'equipment_change_review',
])

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
                      {n.message ? PLAIN_MESSAGE_TYPES.has(n.type) ? (
                        <span className="notification-bell__item-reason">{n.message}</span>
                      ) : (
                        <span className="notification-bell__item-reason">“{n.message}”</span>
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
