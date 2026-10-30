import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import type { NotificationDto } from '../../types/notification'
import { NOTIFICATIONS_CHANGED_EVENT } from './notificationRefresh'

const POLL_INTERVAL_MS = 30_000
const ATTENDEE_POLL_INTERVAL_MS = 5_000

/**
 * EO09/EO19/EC-NEW4: owns fetching, polling and marking read for the
 * signed-in user's own notifications. Keeps EC-NEW4's visibility/focus refresh
 * and 30-second polling for staff. AT03 adds immediate refresh after a saved
 * registration and five-second attendee polling for delayed delivery.
 * Polling (not push) is a deliberate simplification: see docs/decision-log.md.
 */
export function useNotifications() {
  const { role } = useSession()
  const [notifications, setNotifications] = useState<NotificationDto[]>([])
  const [unreadCount, setUnreadCount] = useState(0)
  const [loaded, setLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Bumped to re-trigger the effect below on demand (e.g. Try again)
  // without putting a fetch call inside the effect body itself.
  const [refreshToken, setRefreshToken] = useState(0)
  const requestVersion = useRef(0)
  const appliedRequest = useRef(0)

  useEffect(() => {
    if (!role) return
    let cancelled = false

    function poll() {
      const request = ++requestVersion.current
      Promise.all([
        apiClient.get<NotificationDto[]>('/notifications'),
        apiClient.get<{ count: number }>('/notifications/unread-count'),
      ])
        .then(([list, count]) => {
          if (cancelled || request < appliedRequest.current) return
          appliedRequest.current = request
          setNotifications(list)
          setUnreadCount(count.count)
          setLoaded(true)
          setError(null)
        })
        .catch((err: unknown) => {
          if (cancelled || request < appliedRequest.current) return
          appliedRequest.current = request
          // A failed refresh never breaks the page; the bell says so and offers Try again.
          setError(err instanceof ApiClientError ? err.message : 'Could not load notifications.')
        })
    }

    function onVisible() {
      if (document.visibilityState === 'visible') poll()
    }

    poll()
    window.addEventListener(NOTIFICATIONS_CHANGED_EVENT, poll)
    // Also discover confirmations delivered later by the outbox or from another tab.
    const timer = setInterval(poll, role === 'attendee' ? ATTENDEE_POLL_INTERVAL_MS : POLL_INTERVAL_MS)
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', onVisible)
    return () => {
      cancelled = true
      window.removeEventListener(NOTIFICATIONS_CHANGED_EVENT, poll)
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', onVisible)
    }
  }, [role, refreshToken])

  const refresh = useCallback(() => setRefreshToken((n) => n + 1), [])

  const markRead = useCallback(async (notificationId: string) => {
    appliedRequest.current = ++requestVersion.current
    // Optimistic: marking as read never changes anything else, so there is
    // nothing to roll back if it fails; the next poll re-syncs.
    setNotifications((prev) =>
      prev.map((n) => (n.notificationId === notificationId ? { ...n, read: true } : n)),
    )
    setUnreadCount((prev) => Math.max(0, prev - 1))
    try {
      await apiClient.post<NotificationDto>(`/notifications/${notificationId}/read`)
    } catch {
      // Best-effort; the next poll reconciles either way.
    } finally { appliedRequest.current = ++requestVersion.current }
  }, [])

  /** EC-NEW4: marks read everything up to the newest notice on screen; one
   * that arrives meanwhile stays unread. */
  const markAllRead = useCallback(async () => {
    if (notifications.length === 0) return
    appliedRequest.current = ++requestVersion.current
    const newest = notifications.reduce((a, b) => (Date.parse(b.occurredAt) > Date.parse(a.occurredAt) ? b : a)).occurredAt
    setNotifications((prev) => prev.map((n) => (Date.parse(n.occurredAt) <= Date.parse(newest) ? { ...n, read: true } : n)))
    setUnreadCount(0)
    try {
      await apiClient.post('/notifications/read-all', { upTo: newest })
    } catch {
      // The next poll reconciles.
    } finally { appliedRequest.current = ++requestVersion.current }
    setRefreshToken((n) => n + 1)
  }, [notifications])

  return { notifications, unreadCount, loaded, error, markRead, markAllRead, refresh }
}
