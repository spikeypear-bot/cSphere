// @stories EC-NEW3
import { afterEach, describe, expect, it } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { DecisionCard } from './DecisionCard'
import type { EquipmentRequestStatus } from './reservation.types'

function renderCard(status: EquipmentRequestStatus) {
  render(
    <DecisionCard
      request={{
        requestId: 'request-1', eventId: 'event-1', eventName: 'Town Hall',
        eventStart: '2027-03-10T01:00:00Z', eventEnd: '2027-03-10T04:00:00Z', technicalRequirement: '', status,
      }}
      lines={[]} reservations={[]} ready rejectReason={null} onDecided={() => {}}
    />,
  )
}

describe('EC-NEW3 DecisionCard: statuses set by the coordinator', () => {
  afterEach(cleanup)

  it('EC-NEW3 (D37) (happy): a request a change flagged still reads as approved to Technical Support', () => {
    renderCard('attention')

    expect(screen.getByText(/This request was approved/)).toBeInTheDocument()
    expect(screen.getByText(/the coordinator is reviewing a change/i)).toBeInTheDocument()
    expect(screen.queryByText(/rejected/i)).not.toBeInTheDocument()
  })

  it('EC-NEW3 (D36) (happy): a request replaced by a revision says so, not that it was rejected', () => {
    renderCard('superseded')

    expect(screen.getByText(/replaced by a revision/i)).toBeInTheDocument()
    expect(screen.queryByText(/rejected/i)).not.toBeInTheDocument()
  })
})
