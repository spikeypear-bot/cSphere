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

// Autosave-UX enhancement to EO01: save automatically a short while after the
// person stops typing, not only on step-change/exit/submit, and keep a local
// backup so a dropped connection or closed tab never loses what was typed —
// see docs/decision-log.md D17. The backup is deliberately *not* the source
// of truth: it only fills a gap between "typed" and "server-confirmed saved."
const AUTOSAVE_DEBOUNCE_MS = 1500
const BACKUP_KEY_PREFIX = 'connectsphere.draft-backup.'

// Scoped by organisation as well as id: this browser's storage is shared
// across every organisation "logged in" on it in turn (there is no real
// account/session isolation yet — see decision-log.md D6a), so a key by id
// alone let a stale backup from one organisation answer a load-failure
// fallback for a *different* organisation viewing the same request id after
// a blocked cross-org 404 — a real cross-organisation data leak found via
// testing (EO01-TC4/EO15-TC3), even though the server itself always
// correctly rejected the request. Organisation is part of the key so a
// browser can never surface one organisation's typed content while acting
// as another.
function backupKey(organisation: string | null, id: string | null): string {
  return `${BACKUP_KEY_PREFIX}${organisation ?? 'unknown'}.${id ?? 'new'}`
}

function readBackup(organisation: string | null, id: string | null): DraftFields | null {
  try {
    const raw = window.localStorage.getItem(backupKey(organisation, id))
    return raw ? (JSON.parse(raw) as DraftFields) : null
  } catch {
    return null // Corrupt/unavailable storage is not worth failing the page over.
  }
}

function writeBackup(organisation: string | null, id: string | null, fields: DraftFields): void {
  try {
    window.localStorage.setItem(backupKey(organisation, id), JSON.stringify(fields))
  } catch {
    // Best-effort only — private browsing / full storage just means no
    // safety net this session, not a broken wizard.
  }
}

function clearBackup(organisation: string | null, id: string | null): void {
  try {
    window.localStorage.removeItem(backupKey(organisation, id))
  } catch {
    // Nothing to do if storage is unavailable.
  }
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
  restoredFromLocalBackup: boolean
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
  const [fields, setFieldsState] = useState<DraftFields>(
    () => readBackup(organisation, requestId ?? null) ?? BLANK_DRAFT,
  )
  const [status, setStatus] = useState<EventRequestDto['status'] | null>(null)
  const [loading, setLoading] = useState(Boolean(requestId))
  const [loadError, setLoadError] = useState<string | null>(null)
  const [restoredFromLocalBackup, setRestoredFromLocalBackup] = useState(false)
  const [autosaveState, setAutosaveState] = useState<AutosaveState>('idle')
  const savingRef = useRef(false)
  // No requestId means there is nothing to fetch, so the very first render
  // already reflects "loaded" state — only an existing draft needs the fetch
  // below to complete before autosave is allowed to fire.
  const hasLoadedRef = useRef(!requestId)

  useEffect(() => {
    if (!requestId || !organisation) return
    let cancelled = false
    // `loading`'s initial value (Boolean(requestId), set above) already
    // covers the mount case; not re-setting it here (react-hooks'
    // set-state-in-effect rule) is fine in practice because this component
    // is never re-mounted with a *different* requestId without a full
    // navigation, which remounts it fresh anyway.
    apiClient
      .get<EventRequestDto>(`/event-requests/${requestId}`)
      .then((dto) => {
        if (cancelled) return
        setFieldsState(toDraftFields(dto))
        setStatus(dto.status)
        setId(dto.requestId)
        clearBackup(organisation, requestId) // Server is reachable and authoritative again.
      })
      .catch((error: unknown) => {
        if (cancelled) return
        // Session persistence pattern: a dropped connection shouldn't strand
        // the organiser on a bare error page if there's a local backup of
        // this exact draft to fall back to — let them keep editing. Scoped to
        // *this* organisation, so a cross-org 404 (this request belongs to a
        // different organisation) can never be answered by a backup left
        // behind by that other organisation's own session on this device.
        const backup = readBackup(organisation, requestId)
        if (backup) {
          setFieldsState(backup)
          setId(requestId)
          setRestoredFromLocalBackup(true)
        } else {
          setLoadError(error instanceof ApiClientError ? error.message : 'Could not load this request.')
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
          hasLoadedRef.current = true
        }
      })
    return () => {
      cancelled = true
    }
  }, [requestId, organisation])

  const setFields = useCallback((update: Partial<DraftFields>) => {
    setFieldsState((prev) => ({ ...prev, ...update }))
  }, [])

  // Keep the local backup current on every change, independent of the
  // debounced server autosave below — this is deliberately cheap/immediate
  // since it never leaves the browser.
  useEffect(() => {
    if (!hasLoadedRef.current) return
    writeBackup(organisation, id, fields)
  }, [organisation, id, fields])

  const save = useCallback(async () => {
    if (!organisation || savingRef.current) return
    savingRef.current = true
    setAutosaveState('saving')
    try {
      const body: SaveEventRequestRequest = fields
      const previousId = id
      const dto = id
        ? await apiClient.put<EventRequestDto>(`/event-requests/${id}`, body)
        : await apiClient.post<EventRequestDto>('/event-requests', body)
      setId(dto.requestId)
      setStatus(dto.status)
      setAutosaveState('saved')
      setRestoredFromLocalBackup(false)
      // Confirmed saved server-side — the local safety net for this draft
      // (and its pre-first-save "new" backup, if this was the first save)
      // is no longer needed.
      clearBackup(organisation, dto.requestId)
      if (!previousId) clearBackup(organisation, null)
    } catch {
      // The previously saved version is untouched server-side (see
      // EventRequestService.updateDraft) — just tell the person the retry is
      // safe, don't discard what they typed. The local backup (written on
      // every field change above) still has it regardless.
      setAutosaveState('error')
    } finally {
      savingRef.current = false
    }
  }, [fields, id, organisation])

  // True autosave: save a short while after the person stops typing, not
  // only on step-change/exit/submit. Skipped until the initial load (or its
  // absence, for a new draft) has settled, so mounting doesn't immediately
  // fire a save of whatever was just loaded/restored.
  useEffect(() => {
    if (!hasLoadedRef.current || !organisation) return
    const timer = setTimeout(() => {
      save()
    }, AUTOSAVE_DEBOUNCE_MS)
    return () => clearTimeout(timer)
    // Intentionally keyed on `fields` (what should trigger a debounced save),
    // not on `save` itself, which changes identity every render via its own
    // `fields`/`id` dependency and would otherwise restart the debounce on
    // every keystroke for the wrong reason.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fields, organisation])

  const submit = useCallback(async (): Promise<
    { ok: true } | { ok: false; missingFields: string[] }
  > => {
    if (!organisation || !id) return { ok: false, missingFields: [] }
    try {
      const dto = await apiClient.post<EventRequestDto>(`/event-requests/${id}/submit`)
      setStatus(dto.status)
      clearBackup(organisation, id)
      return { ok: true }
    } catch (error) {
      if (error instanceof ApiClientError && error.status === 422 && error.missingFields) {
        return { ok: false, missingFields: error.missingFields }
      }
      throw error
    }
  }, [id, organisation])

  return {
    fields,
    setFields,
    status,
    loading,
    loadError,
    restoredFromLocalBackup,
    autosaveState,
    save,
    submit,
  }
}
