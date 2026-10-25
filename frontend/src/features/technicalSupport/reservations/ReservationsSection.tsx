import { Card } from '../../../components/ui/Card'
import '../technicalSupport.css'
import type { EquipmentReservation } from './reservation.types'
import { describeReservation } from './reservationUtils'

/** TS02 AC11: the reservations already saved for the event. */
export function ReservationsSection({ reservations }: { reservations: EquipmentReservation[] }) {
  return (
    <Card className="tech-card">
      <h2>Reservations for this event</h2>
      {reservations.length === 0 ? (
        <p>None yet.</p>
      ) : (
        <ul>
          {reservations.map((r) => (
            <li key={r.logId}>
              {r.quantity} × {r.equipmentName}
              {r.serialNumber ? ` (serial ${r.serialNumber})` : ''} —{' '}
              {describeReservation(r.loanedFrom, r.loanedUntil)}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}
