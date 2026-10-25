import type { ReactNode } from 'react'
import { Button } from '../../../components/ui/Button'
import type { Resource } from './useResource'
import '../technicalSupport.css'

/** The three states every console read shows the same way: loading, an
 * error with Try again, and empty. `children` renders the loaded data. */
export function ResourceView<T>({ resource, loadingText, errorText, emptyText, isEmpty, children }: {
  resource: Resource<T>
  loadingText: string
  errorText: string
  emptyText: string
  isEmpty: (data: T) => boolean
  children: (data: T) => ReactNode
}) {
  if (resource.error !== null) {
    return (
      <div role="alert" className="tech-load-error">
        <p>{errorText} {resource.error}</p>
        <Button variant="secondary" onClick={resource.retry}>Try again</Button>
      </div>
    )
  }
  if (resource.loading || resource.data === null) return <p role="status">{loadingText}</p>
  if (isEmpty(resource.data)) return <p className="field-hint">{emptyText}</p>
  return <>{children(resource.data)}</>
}
