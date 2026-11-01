// Text for a role dashboard's header. The hour is read in Singapore time,
// like every time in ConnectSphere.
const hourFormat = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Singapore', hour: 'numeric', hourCycle: 'h23' })

/** "Good morning" before noon, "Good afternoon" before 6 pm, else evening. */
export function greetingFor(now = new Date()): string {
  const hour = Number(hourFormat.format(now))
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

/** "1 request", "2 requests". */
export function countOf(count: number, noun: string): string {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}
