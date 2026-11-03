import { apiClient } from '../../lib/apiClient'
import { useRefreshingResource } from '../../lib/useRefreshingResource'
export interface SafetyData<T> {
  data: T | null
  error: string | null
  loading: boolean
  reload: () => void
  setData: (data: T) => void
}
export function useSafetyData<T>(path: string | null): SafetyData<T> {
  const resource = useRefreshingResource(() => apiClient.get<T>(path!, { cache: 'no-store' }), path)
  return { ...resource, reload: resource.retry }
}
