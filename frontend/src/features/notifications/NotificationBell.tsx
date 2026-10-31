import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useNotifications } from './useNotifications'
import type { NotificationDto } from '../../types/notification'
import { JOURNEY_LABELS } from '../../types/status'
import { formatEventDateTime } from '../venueStaff/formatEventDateTime'
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

/** EC-NEW4: who did it, as people say it. */
const ROLE_LABELS: Record<string, string> = {
  eo: 'Event Organiser', ec: 'Event Coordinator', ecl: 'Event Coordinator Lead', vs: 'Venue Staff',
  technician: 'Technical Support', safety_officer: 'Safety Officer', attendee: 'Attendee',
}

/** EC-NEW4: the messages these types carry are the substance, so they are always shown. */
const MESSAGE_TYPES = new Set(['request_submitted', 'request_assigned', 'request_handed_over', 'equipment_request_decided',
  'venue_booking_rejected', 'venue_booking_approved', 'confirmed_venue_changed',
  'event_reassignment_approved', 'event_reassignment_declined'])

function describe(n: NotificationDto): string {
  if (n.type === 'attendee_event_changed') return `Important updates for "${n.eventName}"`
  if (n.type === 'attendee_event_cancelled') return `"${n.eventName}" has been cancelled`
  if (n.type === 'confirmed_venue_changed') return `Venue update for your confirmed event "${n.eventName}"`
  if (n.type === 'request_submitted') return `New request "${n.eventName}" is waiting to be assigned`
  if (n.type === 'request_assigned') return n.isReassignment ? `"${n.eventName}" was transferred to you` : `"${n.eventName}" was assigned to you`
  if (n.type === 'request_handed_over') return `"${n.eventName}" is no longer assigned to you`
  if (n.type === 'equipment_request_decided') return n.reason ? `Equipment request for "${n.eventName}" was rejected` : `Equipment request for "${n.eventName}" was approved`
  if (n.type === 'venue_suitability_changed' || n.type === 'venue_arrangements_changed') return `Venue arrangement updated for "${n.eventName}"`
  if (n.type === 'venue_requirement_reviewed') return `Venue requirement review recorded for "${n.eventName}"`
  if (n.type === 'venue_requirement_changed') return `Venue requirement review required for "${n.eventName}"`
  if (n.type === 'venue_hold_expired') return `Tentative venue hold expired for "${n.eventName}"`
  if (n.type === 'venue_hold_expiring') return `Tentative venue hold expiring soon for "${n.eventName}"`
  if (n.type === 'event_reassignment_requested') return `Reassignment requested for "${n.eventName}"`
  // EC-NEW4 AC17: the title always names the event; the message (who it moved
  // between, or why it was declined) is shown underneath.
  if (n.type === 'event_reassignment_approved') return `"${n.eventName}" has been reassigned`
  if (n.type === 'event_reassignment_declined') return `Reassignment declined for "${n.eventName}"`
  if (n.type === 'change_request_submitted') return `Change requested for "${n.eventName}"`
  if (n.type === 'change_request_withdrawn') return `Change request withdrawn for "${n.eventName}"`
  if (n.type === 'change_request_decided') return n.message ?? `Your change request for "${n.eventName}" was decided`
  if (n.type === 'request_cancelled') return `The organiser cancelled the request "${n.eventName}"`
  if (n.type === 'event_cancellation_requested') return `Cancellation requested for "${n.eventName}"`
  if (n.type === 'event_cancellation_approved') return `"${n.eventName}" has been cancelled`
  if (n.type === 'event_cancellation_declined') return `Cancellation of "${n.eventName}" was declined`
  if (n.type === 'event_cancelled') return `"${n.eventName}" has been cancelled`
  if (n.type === 'attendee_registration_confirmed') return `Your registration for "${n.eventName}" is confirmed`
  if (n.type === 'attendee_registration_withdrawn') return `Your registration for "${n.eventName}" has been withdrawn`
  // TS09: the outcome itself is the message, shown under the title.
  if (n.type === 'equipment_damage_outcome') return `Damaged equipment affects "${n.eventName}"`
  if (n.type === 'equipment_request_attention') return `The equipment request for "${n.eventName}" needs attention`
  if (n.type === 'venue_timing_changed') return `Venue timings updated for "${n.eventName}"`
  if (n.type === 'venue_timing_conflict') return `Venue timing conflict for "${n.eventName}"`
  if (n.type === 'venue_unavailable') return `A venue booking for "${n.eventName}" is unavailable`
  if (n.type === 'event_confirmed') return `"${n.eventName}" is confirmed`
  if (n.type === 'event_returned_to_planning') return `"${n.eventName}" is back in Planning`
  if (n.type === 'event_safety_approved') return `"${n.eventName}" passed its safety check and is ready to confirm`
  if (n.type === 'clarification_requested') return `Clarification needed on "${n.eventName}"`
  if (n.type === 'clarification_responded') return `"${n.eventName}" was updated and resubmitted`
  if (n.type === 'venue_booking_rejected') return `Venue booking for "${n.eventName}" was rejected`
  if (n.type === 'venue_booking_approved') return `Venue booking for "${n.eventName}" was approved`
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

/** EO09/EO19: unread-count badge + a dropdown of every notification for the
 * signed-in user, newest first. Rendered in AppShell for any signed-in role
 * — today only Organisers receive anything, but nothing here assumes that. */
export function NotificationBell() {
  const { notifications, unreadCount, loaded, error, markRead, markAllRead, refresh } = useNotifications()
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
        {/* EC-NEW4: never wider than "99+"; the label above keeps the exact number. */}
        {unreadCount > 0 ? <span className="notification-bell__badge" aria-hidden="true">{unreadCount > 99 ? '99+' : unreadCount}</span> : null}
      </button>

      {open ? (
        <div className="notification-bell__panel" role="menu">
          <div className="notification-bell__panel-header">
            <h2>Notifications</h2>
            {unreadCount > 0 ? (
              <button type="button" className="notification-bell__mark-all" onClick={() => void markAllRead()}>
                Mark all as read
              </button>
            ) : null}
          </div>
          {error ? (
            <div className="notification-bell__error" role="alert">
              <p>Notifications could not be loaded.</p>
              <button type="button" className="notification-bell__retry" onClick={refresh}>Try again</button>
            </div>
          ) : null}
          {!loaded && !error ? <p className="notification-bell__empty" role="status">Loading notifications…</p> : null}
          {loaded && notifications.length === 0 ? (
            <p className="notification-bell__empty">No notifications yet.</p>
          ) : notifications.length === 0 ? null : (
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
                      {!n.read ? <span className="notification-bell__unread">Unread</span> : null}
                      <span className="notification-bell__item-text">{describe(n)}</span>
                      {n.type === 'status_change' && n.newStatus === 'rejected' && n.reason ? (
                        <span className="notification-bell__item-reason">Reason: {n.reason}</span>
                      ) : null}
                      {n.message && MESSAGE_TYPES.has(n.type) ? (
                        <span className="notification-bell__item-reason">{n.message}</span>
                      ) : null}
                      {n.message && !MESSAGE_TYPES.has(n.type) && n.type !== 'venue_requirement_changed' && n.type !== 'venue_requirement_reviewed' && n.type !== 'venue_suitability_changed' && n.type !== 'venue_arrangements_changed' ? n.type === 'venue_unavailable' || n.type === 'venue_hold_expiring' ? (
                        <span className="notification-bell__item-reason">{n.message}</span>
                      ) : (
                        <span className="notification-bell__item-reason">“{n.message}”</span>
                      ) : null}
                      {/* EC-C4 AC6: the reason Venue Staff gave for the unavailability. */}
                      {n.type === 'venue_unavailable' && n.reason ? (
                        <span className="notification-bell__item-reason">
                          Reason: {n.reason}
                        </span>
                      ) : null}

                      {n.type === 'attendee_event_changed' && n.changes?.length ? (
                        <ul className="notification-bell__item-reason">
                          {n.changes.map((change) => (
                            <li key={change.field}>
                              {change.label}: {change.previousValue} → {change.updatedValue}
                            </li>
                          ))}
                        </ul>
                      ) : null}

                      {n.type === 'attendee_event_cancelled' &&
                       n.originalEventStartDatetime &&
                       n.originalEventEndDatetime ? (
                        <span className="notification-bell__item-reason">
                          Original date/time: {formatEventDateTime(n.originalEventStartDatetime)} to {formatEventDateTime(n.originalEventEndDatetime)} (Singapore time)
                        </span>
                      ) : null}

                      {n.type === 'attendee_event_cancelled' && n.reason ? (
                        <span className="notification-bell__item-reason">Reason: {n.reason}</span>
                      ) : null}
                      {n.actorName ? (
                        <span className="notification-bell__actor">
                          By {n.actorName}{n.actorRole ? ` (${ROLE_LABELS[n.actorRole] ?? n.actorRole})` : ''}
                        </span>
                      ) : null}
                      <span className="notification-bell__item-time">
                        <time dateTime={n.occurredAt}>{timeAgo(n.occurredAt)}</time>
                        <span> · {formatEventDateTime(n.occurredAt)} (Singapore time)</span>
                      </span>
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
