import { useSession } from '../../lib/sessionContext'
import { formatRelativeTime } from '../../lib/relativeTime'
import type { ActivityDto } from '../../types/activity'

/** Entries this close to `updatedAt` are the action that set it. */
const SAME_ACTION_MS = 2000

/**
 * "Acme · submitted 14 days ago by eo1 · last updated 16 minutes ago by you".
 *
 * Both names come from the request's timeline, so they are the people who
 * actually acted. "Last updated by" is the latest timeline entry's actor,
 * unless `updatedAt` is later than that entry: that means the organiser
 * saved edits without resubmitting (saves are not timeline entries), so the
 * updater is the organisation's side, named as such rather than guessed.
 */
export function RequestByline({ organisation, createdAt, updatedAt, timeline, createdByName }: {
  organisation?: string | null
  /** Fallback for requests submitted before the timeline existed. */
  createdByName?: string | null
  createdAt: string
  updatedAt: string
  timeline: ActivityDto[]
}) {
  const { username, role } = useSession()
  const who = (name: string) => (name === username ? 'you' : name)

  const submitted = timeline.find((e) => e.type === 'submitted')
  const submitter = submitted?.actorName ?? createdByName ?? null
  const latest = timeline.length > 0 ? timeline[timeline.length - 1] : null
  const editedSinceLatest = latest
    && new Date(updatedAt).getTime() - new Date(latest.occurredAt).getTime() > SAME_ACTION_MS
  const updater = !latest ? null
    : editedSinceLatest ? (role === 'organiser' ? 'your organisation' : 'the organiser')
    : who(latest.actorName)

  return (
    <p className="field-hint request-byline">
      {organisation ? <>{organisation} · </> : null}
      submitted {formatRelativeTime(submitted?.occurredAt ?? createdAt)}
      {submitter ? <> by {who(submitter)}</> : null}
      {' · '}last updated {formatRelativeTime(updatedAt)}
      {updater ? <> by {updater}</> : null}
    </p>
  )
}
