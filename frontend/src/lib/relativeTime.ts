// EO01/EO15 UX enhancement (docs/decision-log.md D17): "edited 3 minutes ago"
// reads faster on a list of drafts than a full timestamp. Deliberately coarse
// (minutes/hours/days) rather than seconds-precision — a draft list doesn't
// need to be a stopwatch.
export function formatRelativeTime(isoTimestamp: string, now: Date = new Date()): string {
  const then = new Date(isoTimestamp)
  const diffMs = now.getTime() - then.getTime()
  const diffSeconds = Math.round(diffMs / 1000)

  if (diffSeconds < 60) return 'just now'

  const diffMinutes = Math.round(diffSeconds / 60)
  if (diffMinutes < 60) return `${diffMinutes} minute${diffMinutes === 1 ? '' : 's'} ago`

  const diffHours = Math.round(diffMinutes / 60)
  if (diffHours < 24) return `${diffHours} hour${diffHours === 1 ? '' : 's'} ago`

  const diffDays = Math.round(diffHours / 24)
  if (diffDays < 30) return `${diffDays} day${diffDays === 1 ? '' : 's'} ago`

  return then.toLocaleDateString()
}
