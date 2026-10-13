type Period = { startDatetime: string; endDatetime: string }

/** Highlight the boundaries that explain a direct overlap, using full timestamps. */
export function bookingConflictHighlights(request: Period, existing: Period) {
  const start = Date.parse(request.startDatetime), end = Date.parse(request.endDatetime)
  const otherStart = Date.parse(existing.startDatetime), otherEnd = Date.parse(existing.endDatetime)
  if (!(start < otherEnd && end > otherStart)) {
    return { requestStart: false, requestEnd: false, existingStart: false, existingEnd: false }
  }
  const contained = (start <= otherStart && end >= otherEnd) || (otherStart <= start && otherEnd >= end)
  return {
    requestStart: contained || start > otherStart,
    requestEnd: contained || end < otherEnd,
    existingStart: contained || start < otherStart,
    existingEnd: contained || end > otherEnd,
  }
}
