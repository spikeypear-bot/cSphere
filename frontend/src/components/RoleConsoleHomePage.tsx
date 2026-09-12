import { SkeletonFeatureGrid } from './SkeletonFeatureGrid'
import type { SkeletonFeature } from '../types/skeletonFeature'
import './skeleton.css'

export function RoleConsoleHomePage({
  roleTitle,
  basePath,
  features,
}: {
  roleTitle: string
  basePath: string
  features: SkeletonFeature[]
}) {
  return (
    <div className="role-console-home">
      <div className="skeleton-console__header">
        <h1>{roleTitle} console</h1>
        <p className="field-hint">
          None of these pages are built yet — pick a card to see the page and backend package a
          Sprint story will build on. Story IDs shown are this session's best mapping from{' '}
          <code>docs/product-context.md</code>; re-check exact IDs and acceptance criteria on the
          live backlog sheet before starting.
        </p>
      </div>
      <SkeletonFeatureGrid basePath={basePath} features={features} />
    </div>
  )
}
