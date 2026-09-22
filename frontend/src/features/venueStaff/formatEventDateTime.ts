export function formatEventDateTime(value: string) {
  return new Intl.DateTimeFormat('en-SG', { timeZone: 'Asia/Singapore',
    day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: true,
  }).format(new Date(value))
}

