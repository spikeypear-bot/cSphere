# Equipment damage API (TS09)

Technical Support report damaged equipment and decide what happens to each event that relied on it. The event's assigned Event Coordinator sees the outcome and deals with any shortfall. Rules and reasons: decision-log D31 (and D32 for the console reads and the Unavailable block). Tables: `SCHEMA.md` § V35, § V37.

Errors use the usual `ApiError` shape (`{"message": "..."}`): 400 invalid input, 403 wrong role or not the assigned coordinator, 404 not found, 409 not possible in the current state. A refused action changes nothing.

## Technical Support (`TECHNICIAN`)

### `POST /api/equipment-damage-reports` → 201

```json
{
  "equipmentId": "uuid",
  "serialNumber": "PJ-1",
  "quantity": null,
  "description": "Lamp exploded",
  "periodStart": "2027-03-01T00:00:00Z",
  "periodEnd": null,
  "blockStatus": "UNAVAILABLE"
}
```

- Serialised kind: `serialNumber` required (an existing unit); `quantity` omitted or 1.
- Non-serialised kind: `quantity` from 1 to the quantity owned; no `serialNumber`; no block (`blockStatus` or `markFaulty`).
- `blockStatus` (V37): `FAULTY`, `UNAVAILABLE` (any case) or omitted/null — the TS03 block put on the unit when the report closes. The older `"markFaulty": true` is still accepted and means `FAULTY`; `markFaulty: true` with a different `blockStatus` is 400, as is any other value (including `AVAILABLE`).
- `description` 1–2000 characters after trimming. `periodEnd` optional, and after `periodStart` when given.
- A report affecting no event is returned already `CLOSED`.

Returns a **report**:

```json
{
  "reportId": "uuid", "equipmentId": "uuid", "equipmentName": "Projector", "serialised": true,
  "serialNumber": "PJ-1", "quantity": 1, "description": "Lamp exploded",
  "periodStart": "...", "periodEnd": null, "markFaulty": false, "faultyMarked": false,
  "blockStatus": "UNAVAILABLE", "blockApplied": false,
  "status": "OPEN", "reportedBy": "uuid", "reportedByName": "ts1", "reportedAt": "...", "closedAt": null,
  "canClose": false,
  "affectedEvents": [{
    "outcomeId": "uuid", "eventId": "uuid", "eventName": "Gala",
    "eventStart": "...", "eventEnd": "...", "journeyStatus": "CONFIRMED",
    "requestedQuantity": 2, "reservedQuantity": 2, "affectedQuantity": 1,
    "spareCovers": false, "active": true,
    "outcome": null, "shortfall": 0, "reducedBy": 0, "replacementSerialNumber": null, "decidedAt": null,
    "resolution": null, "resolutionNote": null, "resolvedAt": null,
    "additionalRequestId": null, "additionalRequestStatus": null
  }]
}
```

- `requestedQuantity`: what the event's approved requests ask for of this kind, less shortfalls already recorded.
- `reservedQuantity`: units of the kind reserved for the event's whole period right now.
- `affectedQuantity`: the damaged unit's reservations for the event, or (non-serialised) the units it holds during the damage period.
- `spareCovers`: serialised and undecided only; the event still has enough without the damaged unit.
- `active`: false once the event is cancelled or over; it then needs no outcome.
- `outcome`: `null` (undecided), `COVERED`, `REPLACED` or `SHORT`. `resolution`: `NOTE` or `ADDITIONAL_REQUEST`.
- `blockStatus`: the block asked for (`FAULTY`, `UNAVAILABLE` or `null`); `blockApplied`: it was added when the report closed. `markFaulty` (= `blockStatus` is `FAULTY`) and `faultyMarked` (= a `FAULTY` block was added) are kept for older clients.

### `GET /api/equipment-damage-reports` → 200

Every report, newest first, in the shape above. Optional `?equipmentId=uuid` keeps one kind's reports (D32).

### `GET /api/equipment-damage-reports/{reportId}` → 200

### `POST /api/equipment-damage-reports/{reportId}/outcomes/{outcomeId}` → 200 (the updated report)

```json
{ "decision": "REPLACE", "replacementSerialNumber": "PJ-2", "reduceBy": null }
```

| `decision` | Kind | Effect | Refused (409) when |
|---|---|---|---|
| `COVERED` | serialised | Damaged unit's reservation released | the rest would not cover `requestedQuantity` |
| `REPLACE` | serialised | Same reservation, held by `replacementSerialNumber` | that unit is Faulty/Unavailable, reserved, or reported damaged during the reservation (400 if it is the damaged unit; 404 if unknown) |
| `SHORT` | serialised | Damaged unit's reservation released; `shortfall` = what is missing | spares still cover the event |
| `REDUCE` | non-serialised | `reduceBy` (≥ 0) units taken off the event's reservations; `COVERED` or `SHORT` follows | the coordinator already acted on the shortfall (400 if more than the event holds) |

- A serialised outcome is final (409 on a second decision). `REDUCE` can be repeated while the report is open; reductions add up.
- 409 if the report is closed or the event was cancelled or has finished.
- Afterwards the coordinator is notified (`equipment_damage_outcome`), a timeline entry is written, and a `SHORT` event that was awaiting review, safety-approved or confirmed returns to Planning.

### `POST /api/equipment-damage-reports/{reportId}/close` → 200

409 while an active affected event has no outcome, or (non-serialised) reservations still exceed the working units. Adds the block in `blockStatus` (Faulty or Unavailable, through TS03's `EquipmentStatusService.addPeriod`) for the report's period when no event still holds the unit (`blockApplied`).

## Event Coordinator (`EC`) and Lead (`ECL`)

### `GET /api/events/{eventId}/equipment-damage` → 200

The assigned coordinator, or the Lead for any event (read-only). Another coordinator gets 403.

```json
[{
  "outcomeId": "uuid", "reportId": "uuid", "equipmentId": "uuid", "equipmentName": "Projector",
  "serialNumber": "PJ-1", "damagedQuantity": 1, "description": "Lamp exploded",
  "periodStart": "...", "periodEnd": null, "reportedAt": "...",
  "outcome": "SHORT", "shortfall": 1, "replacementSerialNumber": null, "decidedAt": "...",
  "resolution": null, "resolutionNote": null, "resolvedAt": null,
  "additionalRequestId": null, "additionalRequestStatus": null,
  "canRequestAdditional": true, "canResolve": true
}]
```

- `canRequestAdditional`: the shortfall is open, nothing answers it yet, the event is in Planning and no equipment request awaits review. Always false for the Lead.
- `canResolve`: the shortfall is open and no additional request for it awaits review. Always false for the Lead.

### Additional request: `POST /api/events/{eventId}/equipment-request` (EC07) → 201

Add `"shortfallOutcomeId": "uuid"` to the usual EC07 body. Accepted beside an approved request while that shortfall can take a request; still refused (409) while any request for the event awaits review. Technical Support review it as usual (TS01, TS02, TS06). TS06 approval then needs the event's reservations to cover this request plus what its approved requests are still owed; approval resolves the shortfall (`ADDITIONAL_REQUEST`).

### `POST /api/events/{eventId}/equipment-damage/{outcomeId}/resolve` → 200

```json
{ "note": "Organiser agreed one projector is enough" }
```

Assigned coordinator only (the Lead gets 403). `note` 1–2000 characters. 409 if the shortfall is not open or its additional request awaits review. Returns the item in the shape above.

## Notifications

`equipment_damage_outcome`: to the assigned coordinator; `eventId` and `message` set; `linkPath` `/coordinator/events/{eventId}`.

## Technical Support console (D32)

The console reuses and extends existing endpoints rather than adding parallel ones. All are `TECHNICIAN` only unless stated; any other role gets 403 before any lookup. Times are ISO-8601 instants. Windows are half-open: `[from, to)` counts what overlaps it, so something ending exactly at `from` is not counted.

### Requests: `GET /api/equipment-requests?status=processing|approved|rejected|all` → 200

`status` defaults to `processing`; anything else is 400 (`"status must be processing, approved, rejected or all."`). `GET /api/equipment-requests/processing` still works and returns exactly the `?status=processing` list. Requests of cancelled or completed events are left out (EO07). Soonest event start first.

```json
[{
  "requestId": "uuid", "eventId": "uuid", "eventName": "Gala",
  "eventStart": "2027-03-10T01:00:00Z", "eventEnd": "2027-03-10T04:00:00Z",
  "technicalRequirement": "Stage", "status": "processing",
  "organisationName": "Acme Pte Ltd",
  "requestedBy": { "userId": "uuid", "name": "ec1" },
  "submittedAt": null,
  "isShortfallRequest": false,
  "items": [{ "equipmentId": "uuid", "equipmentName": "Projector", "quantity": 2 }],
  "eventStatus": "PLANNING"
}]
```

- The first seven fields are the original shape, unchanged.
- `requestedBy`: the submitting coordinator is not stored, so this is the event's **currently assigned** coordinator (`name` is the username); `null` when the event has none.
- `submittedAt`: not stored yet, so always `null`.
- `isShortfallRequest`: the request is an additional request answering a TS09 shortfall (AC19).
- `eventStatus`: the event's journey status (`JourneyStatus`, D22), e.g. `PLANNING`, `CONFIRMED`.
- One query each for requests, events, lines, coordinators and shortfall links, however many requests.

### Status periods: `GET /api/equipment/status-periods?current=true` → 200

Every unit's TS03 blocks that are running or yet to start (no end, or an end after now), newest start first. `current` defaults to `true`; `current=false` lists every block, ended ones too.

```json
[{ "id": "uuid", "equipmentId": "uuid", "equipmentName": "Projector", "serialNumber": "PJ-1",
   "status": "Faulty", "start": "2027-05-01T00:00:00Z", "end": null }]
```

`status` uses TS03's labels (`Faulty`, `Unavailable`), as `GET /api/equipment/{id}/units/{serial}/periods` does.

### Catalogue for a window: `GET /api/equipment/catalogue?from&to` → 200

Without `from`/`to` the response is unchanged (`equipmentId`, `equipmentName`, `totalQuantity`, `serialised`) and stays open to `EO`, `EC` and `TECHNICIAN`. With **both**, `TECHNICIAN` only (403 for organisers and coordinators), and each kind adds:

```json
{ "equipmentId": "uuid", "equipmentName": "Microphone", "totalQuantity": 5, "serialised": false,
  "reservedQuantity": 2, "outOfActionQuantity": 1, "availableQuantity": 2 }
```

- 400 for only one of them (`"Give both from and to, or neither."`) or `to` not after `from` (`"to must be after from."`).
- `reservedQuantity`: overlapping reservations of events that are not cancelled or completed. `outOfActionQuantity`: serialised units with a Faulty/Unavailable block overlapping the window (each once) plus units of a non-serialised kind reported damaged during it. `availableQuantity` = total − reserved − out of action, never below 0.
- This is the same calculation (`EquipmentAvailabilityService`) as `GET /api/equipment/requests/{requestId}/availability` and TS02's quantity check, so the figures always agree.

### One kind: `GET /api/equipment/{equipmentId}` → 200 (404 unknown)

```json
{ "equipmentId": "uuid", "equipmentName": "Projector", "serialised": true, "totalQuantity": 3,
  "units": [{ "serialNumber": "PJ-1", "status": "Available",
              "statusPeriods": [{ "id": "uuid", "status": "Unavailable", "start": "...", "end": null }] }] }
```

`units` is by serial number and empty for a non-serialised kind. `status` is the unit's status now (a block that has started and not ended, else `Available`); `statusPeriods` are all its blocks, earliest first.

### A kind's reservations: `GET /api/equipment/{equipmentId}/reservations?from&to` → 200

The by-equipment twin of `GET /api/equipment/events/{eventId}/reservations`, built by the same mapper, so each item has the same shape. Reservations overlapping `[from, to)` of events that are not cancelled or completed, earliest first. 400 without both or with `to` not after `from` (`"from and to are required, and to must be after from"`); 404 for an unknown kind. Not open to coordinators: the list names other events (ECL-C7).

```json
[{ "logId": "uuid", "eventId": "uuid", "equipmentId": "uuid", "equipmentName": "Projector",
   "quantity": 1, "serialNumber": "PJ-1",
   "loanedFrom": "2027-03-10T01:00:00Z", "loanedUntil": "2027-03-10T04:00:00Z",
   "eventName": "Gala" }]
```

`eventName` was added to both reservation lists (and to the `POST /api/equipment/reservations` response); the other fields are unchanged.

### Reporting a problem: TS03 first, TS09 when the unit is reserved

There is no combined endpoint. The console sends a block to TS03's `POST /api/equipment/{equipmentId}/units/{serialNumber}/periods` (`{"status":"Faulty"|"Unavailable","start","end"}`, 204). When an event holds the unit during that time TS03 refuses with:

- **409** and `{"message": "This unit is already committed to an event during that time."}`

That is the only 409 this endpoint returns (404 is an unknown unit, 400 bad input), and the message is a constant (`EquipmentStatusService.UNIT_COMMITTED_MESSAGE`). On it, the console falls back to TS09's `POST /api/equipment-damage-reports` with the same unit, period and `blockStatus`, so each affected event is decided and the block is added when the report closes.
