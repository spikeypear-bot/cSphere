import { useEffect, useRef, useState } from 'react'
import type { VenueHold } from '../../types/venueHold'

export const NO_HOLDS: VenueHold[] = []
const EXPIRY = (h: VenueHold): string | undefined => h.expiresAt

/** Server-derived durations avoid trusting the workstation's clock. Hide occupancy
 * at the deadline even if the refresh fails or the scheduled worker is delayed.
 * `at` picks another server deadline, e.g. EC-C3's reminderDueAt; keep it stable. */
export function useHoldDeadline(holds: VenueHold[], refresh?: () => void, at = EXPIRY) {
  const [expired, setExpired] = useState<Set<string>>(() => new Set())
  const refreshRef = useRef(refresh)
  useEffect(() => { refreshRef.current = refresh }, [refresh])
  useEffect(() => {
    const timers = holds.filter(h => h.status === 'active' && at(h)).map(h => {
      const delay = Math.max(0, Date.parse(at(h)!) - Date.parse(h.serverTime))
      // Browsers clamp large delays to one millisecond; periodically reschedule long holds.
      const deadline = Date.now() + delay
      let timer: ReturnType<typeof setTimeout>
      const check = () => {
        const remaining = deadline - Date.now()
        if (remaining > 0) timer = setTimeout(check, Math.min(remaining, 2147483647))
        else {
          setExpired(previous => new Set([...previous, h.holdId]))
          refreshRef.current?.()
        }
      }
      timer = setTimeout(check, Math.min(delay, 2147483647))
      return () => clearTimeout(timer)
    })
    return () => timers.forEach(cancel => cancel())
  }, [holds, at])
  return expired
}
