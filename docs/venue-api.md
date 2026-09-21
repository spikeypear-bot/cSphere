# Venue catalogue API

VS06A catalogue creation and the requested list/detail reads. No booking or approval workflow.

| Method | Path | Success |
|---|---|---|
| POST | `/api/venues` | 201, saved venue DTO and `Location: /api/venues/{id}` |
| GET | `/api/venues` | 200, array ordered by address then UUID; `[]` when empty |
| GET | `/api/venues/{id}` | 200, single venue DTO |

POST fields: `venueAddress`, `venueCapacity`, `supportedLayouts`,
`operatingInformation`, optional `additionalInformation`. Capacity is one shared
integer from 1 through 50,000. The frontend default of 50 is not supplied by this
API. Layout labels: `classroom`, `theatre`, `boardroom`, `banquet`, `exhibition`.
The response adds `venueId` to these fields.

Optional `venueAccessibilities` and `venueFacilities` arrays record venue provisions.
Omitted or null arrays become empty arrays; responses always include both arrays.
Empty means no selections recorded. Accessibility `none` explicitly records no
provisions and cannot be combined with other accessibility selections. Null entries
and duplicate selections return 422; unknown enum labels or malformed types return 400.
Accessibility labels match `AccessibilityFeature`; facility labels are
`audio_visual_equipment`, `air_conditioning`, `breakout_spaces`, `projection`,
`stage`, `dining_area`, and `barbeque_pit`.

The entity stores enum labels as string collections and casts write parameters to
PostgreSQL `accessibilities[]` / `facilities[]`. Existing column types and GIN
indexes are unchanged. This avoids the named-enum-array binding issue documented
in V4 without changing the Venue schema. No filtering or update endpoint is added.

Errors use the shared `ApiError` shape: `message` and optional `missingFields`.
Malformed JSON/types/layout labels or malformed UUIDs return 400; service
validation returns 422; an unknown venue UUID returns 404.

## Current access limitation

These endpoints currently have no server-side role or identity enforcement.
The existing frontend role selector does not authenticate API callers. The
interim access mechanism remains undecided in decision-log D6a/Q2; this API must
not be described as restricted to Venue Staff or as completing authentication.
The list currently returns the whole catalogue without pagination.

## Verification

`VenueControllerTest` exercises POST then GET through MockMvc and real PostgreSQL,
plus invalid-input and not-found responses. Test transactions roll back inserted
venues. `VenueServiceTest` covers creation validation; `VenuePersistenceTest`
checks enum-array round trips, empty selections, and PostgreSQL column types.
