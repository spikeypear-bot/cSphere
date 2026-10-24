/** A moment as people in Singapore read it, e.g. "3 Mar 2027, 09:00 am".
 * Every role sees times in Singapore time, whatever their device's zone. */
export function formatSingaporeDateTime(iso: string): string {
  return new Intl.DateTimeFormat('en-SG', {
    timeZone: 'Asia/Singapore', day: 'numeric', month: 'short', year: 'numeric',
    hour: '2-digit', minute: '2-digit', hour12: true,
  }).format(new Date(iso))
}
