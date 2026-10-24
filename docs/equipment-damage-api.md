# Equipment damage API (TS09)

Technical Support report damaged equipment and decide what happens to each event that relied on it. The event's assigned Event Coordinator sees the outcome and deals with any shortfall. Rules and reasons: decision-log D31. Tables: `SCHEMA.md` § V35.

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
  "markFaulty": true
}
```

- Serialised kind: `serialNumber` required (an existing unit); `quantity` omitted or 1.
- Non-serialised kind: `quantity` from 1 to the quantity owned; no `serialNumber`; `markFaulty` not allowed.
- `description` 1–2000 characters after trimming. `periodEnd` optional, and after `periodStart` when given.
- A report affecting no event is returned already `CLOSED`.

Returns a **report**:

```json
{
  "reportId": "uuid", "equipmentId": "uuid", "equipmentName": "Projector", "serialised": true,
  "serialNumber": "PJ-1", "quantity": 1, "description": "Lamp exploded",
  "periodStart": "...", "periodEnd": null, "markFaulty": true, "faultyMarked": false,
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

### `GET /api/equipment-damage-reports` → 200

Every report, newest first, in the shape above.

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

409 while an active affected event has no outcome, or (non-serialised) reservations still exceed the working units. Adds the Faulty block when `markFaulty` was asked for and no event still holds the unit (`faultyMarked`).

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
