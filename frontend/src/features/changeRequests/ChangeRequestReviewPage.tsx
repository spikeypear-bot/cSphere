import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Button } from '../../components/ui/Button'
import { Card } from '../../components/ui/Card'
import { TextField } from '../../components/ui/fields'
import { Notice } from '../../components/ui/Notice'
import { NotAssignedNotice } from '../../components/ui/NotAssignedNotice'
import { PageHeader } from '../../components/ui/PageHeader'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { isNotAssigned } from '../../lib/notAssigned'
import { HOME_BY_ROLE, useSession } from '../../lib/sessionContext'
import { CHANGE_STATUS_LABELS, type ChangeRequestView, type ImpactItem } from '../../types/changeRequest'
import { ChangesTable, When } from './ChangeRequestsPage'
import './ChangeRequests.css'

const MAX_REASON = 2000
const AREA_LABELS: Record<string, string> = {
  arrangements: 'Arrangements', venue: 'Venues', equipment: 'Equipment', safety: 'Safety check and confirmation',
}

/** The impact, grouped by area; "attention" items can be ticked when `onToggle` is given. */
export function ImpactList({ items, ticked, onToggle }: {
  items: ImpactItem[]
  ticked?: Set<string>
  onToggle?: (key: string) => void
}) {
  const areas = [...new Set(items.map((i) => i.area))]
  return (
    <div className="change-request__impact">
      {areas.map((area) => (
        <section key={area} aria-label={AREA_LABELS[area] ?? area}>
          <h3>{AREA_LABELS[area] ?? area}</h3>
          <ul className="plain-list">
            {items.filter((i) => i.area === area).map((i) => (
              <li key={i.key} className={`change-request__impact-item change-request__impact-item--${i.status}`}>
                <span className="change-request__impact-status">{i.status === 'ok' ? 'OK' : 'Needs attention'}</span>
                <div>
                  <p className="change-request__impact-title">{i.title}</p>
                  <p className="field-hint">{i.detail}</p>
                  {i.status === 'attention' && onToggle ? (
                    <label className="change-request__ack">
                      <input type="checkbox" checked={ticked?.has(i.key) ?? false} onChange={() => onToggle(i.key)} />
                      I have considered this
                    </label>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}

/**
 * EC-NEW3: the assigned coordinator sees what would change, side by side,
 * what it would do to the arrangements already made (recalculated on every
 * visit), acknowledges each item that needs attention, and approves or
 * rejects with a reason. The Lead sees the same, read-only (ECL-C7).
 */
export function ChangeRequestReviewPage() {
  const { changeRequestId } = useParams<{ changeRequestId: string }>()
  const { role } = useSession()
  const [view, setView] = useState<ChangeRequestView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notAssigned, setNotAssigned] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const [ticked, setTicked] = useState<Set<string>>(new Set())
  const [mode, setMode] = useState<'review' | 'reject'>('review')
  const [rejectReason, setRejectReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'success' | 'info' | 'danger'; text: string } | null>(null)
  const base = role ? HOME_BY_ROLE[role] : ''

  useEffect(() => {
    let cancelled = false
    apiClient.get<ChangeRequestView>(`/change-requests/${changeRequestId}`)
      .then((result) => {
        if (!cancelled) {
          setView(result)
          setError(null)
        }
      })
      .catch((err: unknown) => {
        if (cancelled) return
        if (isNotAssigned(err)) setNotAssigned(true)
        setError(err instanceof ApiClientError ? err.message : 'This change request could not be loaded.')
      })
    return () => { cancelled = true }
  }, [changeRequestId, attempt])

  if (notAssigned) return <NotAssignedNotice what="request" />
  if (error) {
    return (
      <div className="page">
        <div role="alert" className="change-request__error">
          <p>This change request or its impact could not be loaded.</p>
          <Button variant="secondary" onClick={() => setAttempt((n) => n + 1)}>Try again</Button>
        </div>
      </div>
    )
  }
  if (!view) return <p role="status">Loading the change and its impact…</p>

  const attention = (view.impact ?? []).filter((i) => i.status === 'attention')
  const allTicked = attention.every((i) => ticked.has(i.key))
  const rejectLength = rejectReason.trim().length

  function toggle(key: string) {
    setTicked((t) => {
      const next = new Set(t)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  async function decide(path: 'approve' | 'reject', body: unknown) {
    setBusy(true)
    setNotice(null)
    try {
      const decided = await apiClient.post<ChangeRequestView>(`/change-requests/${changeRequestId}/${path}`, body)
      setView(decided)
      setNotice({ tone: 'success', text: path === 'approve'
        ? 'Change approved and applied. The organiser has been told.'
        : 'Change rejected. The organiser has been told why.' })
    } catch (err) {
      if (err instanceof ApiClientError && err.status === 409) {
        // The impact or the request changed while this page was open: show the latest.
        setTicked(new Set())
        setAttempt((n) => n + 1)
        setNotice({ tone: 'info', text: err.message })
      } else {
        setNotice({ tone: 'danger', text: err instanceof ApiClientError ? err.message
          : 'The decision could not be saved. Nothing has changed; please try again.' })
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="page change-request">
      <PageHeader
        eyebrow={<Link to={`${base}/requests/${view.requestId}/changes`}>All change requests for this event</Link>}
        title={`Change to ${view.eventName}`}
        description={view.organisation}
        actions={<span className={`change-request__status change-request__status--${view.status}`}>
          {view.closedReason ? 'Closed' : CHANGE_STATUS_LABELS[view.status]}</span>}
      />
      {notice ? <Notice tone={notice.tone}>{notice.text}</Notice> : null}
      {view.closedReason ? <Notice tone="info">{view.closedReason}</Notice> : null}
      {role === 'coordinator-lead' && view.status === 'pending' ? (
        <Notice tone="info">You can see this change and its impact; only the event's coordinator can decide it.</Notice>
      ) : null}

      <Card>
        <p className="field-hint">Asked by {view.requestedByName}, <When iso={view.requestedAt} /></p>
        <ChangesTable view={view} />
        <p className="change-request__reason"><strong>The organiser's reason:</strong> {view.reason}</p>
      </Card>

      {view.impact ? (
        <Card>
          <h2>What this change would affect</h2>
          <p className="field-hint">Checked against the current arrangements just now, including each venue's setup and turnaround time.</p>
          <ImpactList items={view.impact} ticked={ticked} onToggle={view.canDecide ? toggle : undefined} />
        </Card>
      ) : null}

      {view.status !== 'pending' && view.decidedAt ? (
        <Card>
          <p>{view.status === 'withdrawn' ? 'Withdrawn by the organiser' : `${CHANGE_STATUS_LABELS[view.status]} by ${view.decidedByName}`}, <When iso={view.decidedAt} /></p>
          {view.decisionReason ? <p><strong>Reason:</strong> {view.decisionReason}</p> : null}
          {view.followUps.length > 0 ? (
            <>
              <h3>Still to follow up</h3>
              <ImpactList items={view.followUps} />
            </>
          ) : null}
        </Card>
      ) : null}

      {view.canDecide ? (
        mode === 'review' ? (
          <div className="change-request__actions">
            <Button variant="secondary" onClick={() => setMode('reject')}>Reject…</Button>
            <Button disabled={busy || !allTicked} onClick={() => void decide('approve', { acknowledged: attention.map((i) => i.key) })}>
              {busy ? 'Approving…' : 'Approve and apply the change'}
            </Button>
            {!allTicked ? <p className="field-hint" role="status">Tick each item that needs attention to approve.</p> : null}
          </div>
        ) : (
          <Card>
            <TextField id="reject-reason" label="Why can't this change be made?" multiline value={rejectReason}
              onChange={setRejectReason} hint={`${rejectLength} / ${MAX_REASON} characters. The organiser sees this.`}
              error={rejectLength > MAX_REASON ? `Keep it to ${MAX_REASON} characters.` : undefined} />
            <div className="change-request__actions">
              <Button variant="secondary" onClick={() => setMode('review')}>Back</Button>
              <Button disabled={busy || rejectLength === 0 || rejectLength > MAX_REASON}
                onClick={() => void decide('reject', { reason: rejectReason })}>
                {busy ? 'Rejecting…' : 'Reject the change'}
              </Button>
            </div>
          </Card>
        )
      ) : null}
    </div>
  )
}
