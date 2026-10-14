export function validMinutes(value: string | number | null | undefined): boolean {
  if (value === null || value === undefined || String(value).trim() === '') return false
  const number = Number(value)
  return Number.isInteger(number) && number >= 0 && number <= 10080
}

export function occupiedPeriod(start: string, end: string, setup: number, turnaround: number) {
  return {
    effectiveStart: new Date(Date.parse(start) - setup * 60000).toISOString(),
    effectiveEnd: new Date(Date.parse(end) + turnaround * 60000).toISOString(),
  }
}
