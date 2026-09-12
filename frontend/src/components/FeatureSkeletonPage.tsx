import { Card } from './ui/Card'
import type { SkeletonFeature, SkeletonLayout } from '../types/skeletonFeature'
import './skeleton.css'

/**
 * The detail page shown for any not-yet-built story. Deliberately does not
 * call the API or hold real form state — it exists so the page, route, and
 * "what goes here" shape already exist for whoever picks up this story next
 * sprint. Build the real thing the same way EO01/EO02/EO15 were built: full
 * vertical slice (backend entity → service → controller → tests → this page
 * → its own tests → a live end-to-end check) — see AI_Context.md's "strand of
 * hair" workflow.
 */
export function FeatureSkeletonPage({ feature }: { feature: SkeletonFeature }) {
  return (
    <div className="feature-skeleton">
      <div className="feature-skeleton__header">
        <div>
          <p className="field-hint">{feature.featureArea}</p>
          <h1>{feature.pageTitle}</h1>
        </div>
        <span className="feature-skeleton__badge">Skeleton — not built yet</span>
      </div>

      <p className="feature-skeleton__summary">{feature.summary}</p>

      {feature.storyIds.length > 0 ? (
        <div className="feature-skeleton__stories" aria-label="Backlog stories this page belongs to">
          {feature.storyIds.map((id) => (
            <span key={id} className="chip">
              {id}
            </span>
          ))}
        </div>
      ) : null}

      <Card className="feature-skeleton__body">
        <LayoutPreview layout={feature.layout} />
      </Card>

      <Card className="feature-skeleton__strand">
        <h2>Build this as one "strand of hair"</h2>
        <p>
          Nothing on this page talks to the backend yet — that's the next step, not a separate
          one. Re-check this story's exact acceptance criteria on the live backlog sheet, then
          build backend-to-frontend in one pass, testing at every stage:
        </p>
        <ol>
          <li>
            Backend: new <code>{feature.suggestedBackendPackage}</code> package — entity, DTO,
            mapper, repository, service, controller (same layering as <code>eventrequest</code>).
          </li>
          <li>Backend tests: this story's acceptance criteria as automated tests (positive + negative cases).</li>
          <li>Frontend: wire this page up to the real endpoint, replacing this placeholder.</li>
          <li>Frontend tests: cover the same acceptance criteria from the UI side.</li>
          <li>
            A live end-to-end check against the running app (<code>docker compose up --build</code> +
            a real click-through or <code>curl</code>) — mocked tests alone won't catch everything,
            per <code>docs/decision-log.md</code> D10.
          </li>
        </ol>
        <p className="field-hint">See AI_Context.md's "Engineering Workflow" for the full version of this.</p>
      </Card>
    </div>
  )
}

function LayoutPreview({ layout }: { layout: SkeletonLayout }) {
  switch (layout) {
    case 'list':
      return (
        <ul className="feature-skeleton__list-preview" aria-hidden="true">
          <li />
          <li />
          <li />
        </ul>
      )
    case 'form':
      return (
        <div className="feature-skeleton__form-preview" aria-hidden="true">
          <span className="feature-skeleton__line" style={{ width: '35%' }} />
          <span className="feature-skeleton__block" />
          <span className="feature-skeleton__line" style={{ width: '55%' }} />
          <span className="feature-skeleton__block" />
        </div>
      )
    case 'calendar':
      return (
        <div className="feature-skeleton__calendar-preview" aria-hidden="true">
          {Array.from({ length: 28 }, (_, i) => (
            <span key={i} />
          ))}
        </div>
      )
    case 'detail-actions':
      return (
        <div className="feature-skeleton__detail-preview" aria-hidden="true">
          <span className="feature-skeleton__block" style={{ height: '4rem' }} />
          <div className="feature-skeleton__actions">
            <button type="button" className="button button--primary" disabled>
              Approve
            </button>
            <button type="button" className="button button--secondary" disabled>
              Reject
            </button>
          </div>
        </div>
      )
  }
}
