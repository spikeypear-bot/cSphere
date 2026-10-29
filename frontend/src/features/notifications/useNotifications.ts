import { useCallback, useEffect, useState } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import type { NotificationDto } from '../../types/notification'
import { NOTIFICATIONS_CHANGED_EVENT } from './notificationRefresh'

const POLL_INTERVAL_MS = 30_000
const ATTENDEE_POLL_INTERVAL_MS = 5_000

/**
 * EO09/EO19: owns fetching/polling/marking-read for the signed-in user's own
 * notifications. Polling (not push) is a deliberate, disclosed simplification
 * — see docs/decision-log.md: a real-time channel (WebSocket/SSE) is a
 * reasonable future enhancement, not required by either story's ACs, which
 * ask that the notification exist and be viewable. AT03 also triggers an
 * immediate server refresh after registration succeeds; polling remains
 * the fallback for delayed outbox delivery and changes from other sessions.
 */
export function useNotifications() {
  const { role } = useSession()
  const [notifications, setNotifications] = useState<NotificationDto[]>([])
  const [unreadCount, setUnreadCount] = useState(0)
  const [error, setError] = useState<string | null>(null)
  // Bumped to re-trigger the effect below on demand (e.g. right after
  // markRead) without putting a fetch call inside the effect body itself —
  // same inline-fetch-effect shape as useEventRequestDraft.ts.
  const [refreshToken, setRefreshToken] = useState(0)

  useEffect(() => {
    if (!role) return
    let cancelled = false
    let latestRequest = 0

    function poll() {
      const request = ++latestRequest
      Promise.all([
        apiClient.get<NotificationDto[]>('/notifications'),
        apiClient.get<{ count: number }>('/notifications/unread-count'),
      ])
        .then(([list, count]) => {
          if (cancelled || request !== latestRequest) return
          setNotifications(list)
          setUnreadCount(count.count)
          setError(null)
        })
        .catch((err: unknown) => {
          if (cancelled || request !== latestRequest) return
          // A failed refresh should never crash the bell — just try again on
          // the next poll, same "don't let notification plumbing break
          // anything else" principle as the backend's own best-effort save.
          setError(err instanceof ApiClientError ? err.message : 'Could not load notifications.')
        })
    }

    poll()
    window.addEventListener(NOTIFICATIONS_CHANGED_EVENT, poll)
    window.addEventListener('focus', poll)
    const refreshVisible = () => { if (document.visibilityState === 'visible') poll() }
    document.addEventListener('visibilitychange', refreshVisible)
    // Also discover confirmations delivered later by the outbox or from another tab.
    const timer = setInterval(poll, role === 'attendee' ? ATTENDEE_POLL_INTERVAL_MS : POLL_INTERVAL_MS)
    return () => {
      cancelled = true
      window.removeEventListener(NOTIFICATIONS_CHANGED_EVENT, poll)
      window.removeEventListener('focus', poll)
      document.removeEventListener('visibilitychange', refreshVisible)
      clearInterval(timer)
    }
  }, [role, refreshToken])

  const refresh = useCallback(() => setRefreshToken((n) => n + 1), [])

  const markRead = useCallback(async (notificationId: string) => {
    // Optimistic — EO09/EO19's own AC says marking as read never changes
    // anything else, so there is nothing to roll back if the request fails;
    // worst case the next poll re-syncs the true state.
    setNotifications((prev) =>
      prev.map((n) => (n.notificationId === notificationId ? { ...n, read: true } : n)),
    )
    setUnreadCount((prev) => Math.max(0, prev - 1))
    try {
      await apiClient.post<NotificationDto>(`/notifications/${notificationId}/read`)
    } catch {
      // Best-effort; the next poll (30s) reconciles either way.
    }
  }, [])

  return { notifications, unreadCount, error, markRead, refresh }
}
