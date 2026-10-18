import { Link } from 'react-router-dom'
import { Card } from './Card'

/**
 * ECL-C7: what a coordinator sees when a request or event is not assigned to
 * them, for example after the Event Coordinator Lead reassigned it and they
 * open a link from an earlier notification. A clear explanation and a way
 * back, instead of an error; nothing about the request or event itself.
 */
export function NotAssignedNotice({ what = 'event' }: { what?: 'event' | 'request' }) {
  return (
    <div className="page page--compact">
      <Card className="not-assigned" role="region" aria-labelledby="not-assigned-heading">
        <h1 id="not-assigned-heading">This {what} isn't assigned to you</h1>
        <p>
          It may have been reassigned to another Event Coordinator by the Event Coordinator Lead, or it was never
          yours. Only the coordinator it is assigned to can open or work on it.
        </p>
        <p className="field-hint">If you think this is a mistake, ask the Event Coordinator Lead.</p>
        <div className="not-assigned__links">
          <Link className="button button--primary" to="/coordinator/my-events">Go to My events</Link>
          <Link className="button button--secondary" to="/coordinator/review-queue">Go to the review queue</Link>
        </div>
      </Card>
    </div>
  )
}
