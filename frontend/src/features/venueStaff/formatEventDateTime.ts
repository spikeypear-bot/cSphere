import { formatSingaporeDateTime } from '../../lib/singaporeTime'

export function formatEventDateTime(value: string) {
  return formatSingaporeDateTime(value)
}

export function formatEventTimeRange(start: string, end: string) {
  return `${formatEventDateTime(start)} – ${formatEventDateTime(end)}`
}

