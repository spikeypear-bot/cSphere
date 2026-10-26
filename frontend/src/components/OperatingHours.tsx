import type { VenueOperatingHour } from '../types/venue'
import './OperatingHours.css'

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
function time(value: string): string | null {
  const match = /^(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/.exec(value)
  if (!match) return null
  const hour = Number(match[1]), minute = Number(match[2]), second = Number(match[3] ?? 0)
  if (hour > 23 || minute > 59 || second > 59) return null
  return `${hour % 12 || 12}${minute || second ? ':' + String(minute).padStart(2, '0') : ''}${second ? ':' + String(second).padStart(2, '0') : ''}${hour < 12 ? 'am' : 'pm'}`
}
/** Never interpret legacy free text as an opening schedule. */
export function OperatingHours({ hours }: { hours?: VenueOperatingHour[] | null }) {
  const rows = (hours ?? []).filter(h => Number.isInteger(h.dayOfWeek) && h.dayOfWeek >= 1 && h.dayOfWeek <= 7
    && time(h.openTime) && time(h.closeTime) && h.closeTime > h.openTime).toSorted((a, b) => a.dayOfWeek - b.dayOfWeek)
  if (!rows.length) return <span className="operating-hours__empty">Operating hours not configured</span>
  return <div className="operating-hours">
    <ul className="operating-hours__rows" aria-label="Weekly operating hours">
      {rows.map(h => <li className="operating-hours__row" key={h.dayOfWeek}>
        <span className="operating-hours__day">{DAYS[h.dayOfWeek - 1]}</span>
        <span className="operating-hours__time">{time(h.openTime)} – {time(h.closeTime)}</span>
      </li>)}
    </ul>
    <span className="operating-hours__timezone">Singapore time</span>
  </div>
}
