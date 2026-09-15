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
checks array persistence and defaults for unmapped venue fields.
