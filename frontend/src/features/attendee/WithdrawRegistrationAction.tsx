import { useEffect, useRef, useState } from 'react'
import { Button } from '../../components/ui/Button'
import { Notice } from '../../components/ui/Notice'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { requestNotificationRefresh } from '../notifications/notificationRefresh'
import { requestAttendeeRefresh } from './attendeeRefresh'
import { registrationDateTime, type RegistrationDetails } from './registrationView'

export function WithdrawRegistrationAction({ registration, onUpdated, onRefresh }: {
  registration: RegistrationDetails
  onUpdated: (value: RegistrationDetails) => void
  onRefresh: () => Promise<RegistrationDetails | undefined>
}) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [needsRefresh, setNeedsRefresh] = useState(false)
  const [message, setMessage] = useState<{ text: string; failed: boolean } | null>(null)
  const inFlight = useRef(false)
  const path = `/attendee/registrations/${encodeURIComponent(registration.registrationId)}`

  async function readCurrent() {
    const current = await onRefresh()
    if (!current) return
    setNeedsRefresh(false)
    if (current.registrationStatus === 'withdrawn') {
      setMessage({ text: 'Your registration has been withdrawn.', failed: false })
      requestNotificationRefresh()
      requestAttendeeRefresh()
    }
  }

  async function withdraw() {
    if (inFlight.current || !registration.withdrawalAvailable || needsRefresh) return
    inFlight.current = true
    setBusy(true)
    setMessage(null)
    try {
      const saved = await apiClient.post<RegistrationDetails>(`${path}/withdraw`)
      onUpdated(saved)
      setOpen(false)
      setMessage({ text: 'Your registration has been withdrawn.', failed: false })
      requestNotificationRefresh()
      requestAttendeeRefresh()
    } catch (error) {
      setOpen(false)
      setNeedsRefresh(true)
      setMessage({ text: error instanceof ApiClientError && error.status < 500 ? error.message
        : 'Withdrawal could not be confirmed. Refresh your registration status before trying again.', failed: true })
      // A lost response may follow a committed withdrawal. Read before allowing another POST.
      try { await readCurrent() } catch { /* Keep retry disabled until a server read succeeds. */ }
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }

  async function refresh() {
    if (inFlight.current) return
    inFlight.current = true
    setBusy(true)
    try { await readCurrent() } catch {
      setMessage({ text: 'Registration status could not be loaded. Check your connection and try again.', failed: true })
    } finally { inFlight.current = false; setBusy(false) }
  }

  return <section className="attendee-withdrawal" aria-label="Withdraw registration">
    {message && <Notice tone={message.failed ? 'danger' : 'success'}>{message.text}</Notice>}
    {needsRefresh && <Button variant="secondary" disabled={busy} onClick={() => void refresh()}>Refresh registration status</Button>}
    {registration.withdrawalAvailable && <Button variant="secondary" disabled={busy || needsRefresh}
      onClick={() => setOpen(true)}>Withdraw Registration</Button>}
    {open && <WithdrawalDialog registration={registration} busy={busy} onClose={() => setOpen(false)} onConfirm={() => void withdraw()} />}
  </section>
}

function WithdrawalDialog({ registration, busy, onClose, onConfirm }: {
  registration: RegistrationDetails; busy: boolean; onClose: () => void; onConfirm: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => { dialog.current?.showModal() }, [])
  return <dialog ref={dialog} className="attendee-withdrawal-dialog" aria-labelledby="withdrawal-title"
    aria-describedby="withdrawal-description" onCancel={event => { event.preventDefault(); if (!busy) onClose() }}>
    <h2 id="withdrawal-title">Withdraw your registration?</h2>
    <p id="withdrawal-description">Your place will be released. Your registration reference will remain in your history.</p>
    <dl className="attendee-event-details__facts">
      <div><dt>Event</dt><dd>{registration.eventName}</dd></div>
      <div><dt>Registration reference</dt><dd>{registration.reference}</dd></div>
      <div><dt>Starts</dt><dd>{registrationDateTime(registration.startDatetime)}</dd></div>
    </dl>
    {!registration.withdrawalAvailable && <Notice tone="info">{registration.withdrawalReason}</Notice>}
    <div className="attendee-withdrawal-dialog__actions">
      <Button variant="secondary" autoFocus disabled={busy} onClick={onClose}>Keep registration</Button>
      <Button disabled={busy || !registration.withdrawalAvailable} onClick={onConfirm}>{busy ? 'Withdrawing…' : 'Confirm withdrawal'}</Button>
    </div>
  </dialog>
}
