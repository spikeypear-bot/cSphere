// Mirrors backend/src/main/java/com/example/connect_sphere/event/dto/EventCancellationRequestDto.java (EO07)

export type EventCancellationStatus = 'pending' | 'approved' | 'declined'

export interface EventCancellationRequestDto {
  cancellationRequestId: string
  eventId: string
  eventName: string
  requesterName: string | null
  reason: string
  status: EventCancellationStatus
  requestedAt: string
  decidedByName: string | null
  decidedAt: string | null
  decisionReason: string | null
}

/** EO07 AC4: the server's limit, checked here too so the user hears at once. */
export const MAX_CANCELLATION_REASON = 2000

/** The event statuses an organiser may ask to cancel (Planning to Confirmed). */
export const CANCELLABLE_EVENT_STATUSES = [
  'pending', 'awaiting_safety_review', 'safety_changes_requested', 'safety_approved', 'confirmed',
] as const

/** A reason the server would accept, or the message to show instead. */
export function reasonProblem(reason: string, blankMessage: string): string | null {
  const trimmed = reason.trim()
  if (!trimmed) return blankMessage
  if (trimmed.length > MAX_CANCELLATION_REASON) {
    return `A reason can be at most ${MAX_CANCELLATION_REASON} characters (this one is ${trimmed.length}).`
  }
  return null
}
