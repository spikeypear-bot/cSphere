import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, ApiClientError } from '../../lib/apiClient'
import { useSession } from '../../lib/sessionContext'
import type {
  AccessibilityFeature,
  EventRequestDto,
  SaveEventRequestRequest,
} from '../../types/eventRequest'
import type { AutosaveState } from '../../components/ui/AutosaveIndicator'

/** Local form shape — every free-text/optional field is nullable (mirrors
 * SaveEventRequestRequest, which allows the same for a draft), but
 * accessibilityNeeds is always an array so ChipGroup never has to handle a
 * null selection. */
export interface DraftFields {
  eventName: string | null
  purpose: string | null
  description: string | null
  startDatetime: string | null
  endDatetime: string | null
  expectedAttendance: number | null
  venueRequirements: string | null
  equipmentRequirements: string | null
  accessibilityNeeds: AccessibilityFeature[]
  registrationNeeds: boolean | null
}

const BLANK_DRAFT: DraftFields = {
  eventName: null,
  purpose: null,
  description: null,
  startDatetime: null,
  endDatetime: null,
  expectedAttendance: null,
  venueRequirements: null,
  equipmentRequirements: null,
  accessibilityNeeds: [],
  registrationNeeds: null,
}

function toDraftFields(dto: EventRequestDto): DraftFields {
  return {
    eventName: dto.eventName,
    purpose: dto.purpose,
    description: dto.description,
    startDatetime: dto.startDatetime,
    endDatetime: dto.endDatetime,
    expectedAttendance: dto.expectedAttendance,
    venueRequirements: dto.venueRequirements,
    equipmentRequirements: dto.equipmentRequirements,
    accessibilityNeeds: dto.accessibilityNeeds,
    registrationNeeds: dto.registrationNeeds,
  }
}

interface UseEventRequestDraftResult {
  fields: DraftFields
  setFields: (update: Partial<DraftFields>) => void
  status: EventRequestDto['status'] | null
  loading: boolean
  loadError: string | null
  autosaveState: AutosaveState
  save: () => Promise<void>
  submit: () => Promise<{ ok: true } | { ok: false; missingFields: string[] }>
}

/**
 * Owns EO01 (create/reopen/edit a draft) and EO02 (submit) for the wizard
 * page. One request per organisation-scoped id, loaded once and then kept in
 * sync with what the backend has saved.
 */
export function useEventRequestDraft(requestId: string | undefined): UseEventRequestDraftResult {
  const { organisation } = useSession()
  const [id, setId] = useState<string | null>(requestId ?? null)
  const [fields, setFieldsState] = useState<DraftFields>(BLANK_DRAFT)
  const [status, setStatus] = useState<EventRequestDto['status'] | null>(null)
  const [loading, setLoading] = useState(Boolean(requestId))
  const [loadError, setLoadError] = useState<string | null>(null)
  const [autosaveState, setAutosaveState] = useState<AutosaveState>('idle')
  const savingRef = useRef(false)

  useEffect(() => {
    if (!requestId || !organisation) return
    let cancelled = false
    // `loading`'s initial value (Boolean(requestId), set above) already
    // covers the mount case; not re-setting it here (react-hooks'
    // set-state-in-effect rule) is fine in practice because this component
    // is never re-mounted with a *different* requestId without a full
    // navigation, which remounts it fresh anyway.
    apiClient
      .get<EventRequestDto>(`/event-requests/${requestId}`, organisation)
      .then((dto) => {
        if (cancelled) return
        setFieldsState(toDraftFields(dto))
        setStatus(dto.status)
        setId(dto.requestId)
      })
      .catch((error: unknown) => {
        if (cancelled) return
        setLoadError(error instanceof ApiClientError ? error.message : 'Could not load this request.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [requestId, organisation])

  const setFields = useCallback((update: Partial<DraftFields>) => {
    setFieldsState((prev) => ({ ...prev, ...update }))
  }, [])

  const save = useCallback(async () => {
    if (!organisation || savingRef.current) return
    savingRef.current = true
    setAutosaveState('saving')
    try {
      const body: SaveEventRequestRequest = fields
      const dto = id
        ? await apiClient.put<EventRequestDto>(`/event-requests/${id}`, body, organisation)
        : await apiClient.post<EventRequestDto>('/event-requests', body, organisation)
      setId(dto.requestId)
      setStatus(dto.status)
      setAutosaveState('saved')
    } catch {
      // The previously saved version is untouched server-side (see
      // EventRequestService.updateDraft) — just tell the person the retry is
      // safe, don't discard what they typed.
      setAutosaveState('error')
    } finally {
      savingRef.current = false
    }
  }, [fields, id, organisation])

  const submit = useCallback(async (): Promise<
    { ok: true } | { ok: false; missingFields: string[] }
  > => {
    if (!organisation || !id) return { ok: false, missingFields: [] }
    try {
      const dto = await apiClient.post<EventRequestDto>(`/event-requests/${id}/submit`, {}, organisation)
      setStatus(dto.status)
      return { ok: true }
    } catch (error) {
      if (error instanceof ApiClientError && error.status === 422 && error.missingFields) {
        return { ok: false, missingFields: error.missingFields }
      }
      throw error
    }
  }, [id, organisation])

  return { fields, setFields, status, loading, loadError, autosaveState, save, submit }
}
