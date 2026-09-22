// AC 1: an event that needs equipment (status = processing)
export interface EquipmentRequestSummary {
  requestId: string
  eventId: string
  eventName: string
  eventStart: string
  eventEnd: string
  technicalRequirement: string
}

// AC 2: one line of what an event's equipment request asks for
export interface RequestLine {
  equipmentId: string
  equipmentName: string
  quantity: number
}

// AC 3 + 4: availability for one equipment type, for a chosen period
export interface EquipmentAvailability {
  equipmentId: string
  equipmentName: string
  serialised: boolean
  totalQuantity: number
  availableQuantity: number
}

// AC 11: a reservation already saved for an event
export interface EquipmentReservation {
  logId: string
  eventId: string
  equipmentId: string
  equipmentName: string
  quantity: number
  serialNumber: string | null
  loanedFrom: string
  loanedUntil: string
}

// Values from <input type="datetime-local">, e.g. "2026-09-25T09:00"
export interface TimePeriod {
  start: string
  end: string
}