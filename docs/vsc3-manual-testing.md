# VS-C3: demo data and manual testing

Use this guide to demonstrate real time-limited holds, competing requests,
automatic expiry, release, conversion and retained history. The fixture uses the
application APIs, including organiser submission, Lead assignment and coordinator
booking requests. It does not bypass validation or insert synthetic lifecycle
states with SQL.

## Start and seed

From the repository root in PowerShell:

```powershell
docker compose up -d --build backend
# Wait until csphere-backend is healthy; rebuilding applies V29.
docker compose ps
./backend/dev/seed/vsc3-seed.ps1 -LocalDev -ExpiryMinutes 3
```

Start the frontend in another terminal with `npm run dev`, or use the running
frontend. Sign in as **vs1 / 123456** for staff actions. Use a separate browser
profile/private window signed in as **ec1 / 123456** for coordinator notifications
and history. `eo1` owns the demo requests; `ecl1` assigns them to `ec1` during setup.
All four use the existing local dev password `123456`.

The script prints the event date, live expiry in SGT, booking routes and
coordinator routes. It writes the same IDs/paths to
`logs/vsc3/<batch>.json` (ignored by Git; contains no tokens or passwords).

Parameters:

- `-ExpiryMinutes 1`: fast live expiry; default is 3. The countdown starts last,
  after all other scenarios are ready. Conversion holds have a two-hour expiry.
- `-DaysAhead 1`: default booking date is tomorrow in Singapore; optional 1–90.
- `-BaseUrl http://localhost:18083`: alternate local backend root, without `/api`.
- `-BatchName my-demo`: optional recognizable name, letters/numbers/hyphens only.

The seeder requires `-LocalDev` and a loopback URL. It checks that the backend
exposes the hold API before creating demo records. These are accident guards;
run only against your local development database.

Each run creates a **new batch: four dedicated venues, seven events and seven
bookings**. Rerunning an explicit existing batch name refuses; existing data and
your manual edits are never reset. The default batch name is unique. Expiry is
relative to the server run, rather than a committed static timestamp. To repeat
an expired/consumed scenario, run the seeder again for a new batch.

## Find the scenarios

Staff can use the exact routes printed by the script, or use **Booking Approvals** and its separate **Pending booking requests** / **Tentative venue holds** sections with counted filters. Alternatively, open **Venue Catalogue**
and search for the batch's `[DEMO VS-C3 ...]` venue. **Pending Booking Requests**
also contains its pending requests. Coordinator paths end in
`/coordinator/events/<eventId>/venue-booking`.

All scenarios request **10:00–12:00 SGT** on the printed date. Saved setup is
30 minutes and turnaround is 45 minutes: the held occupied period is therefore
**09:30–12:45 SGT**. Each scenario has its own venue, so the scenarios do not
interfere with each other or with existing bookings.

| Scenario label | Initial state | Use it for |
|---|---|---|
| Create and release | Primary + competing request, both pending; no hold | Create, invalid input, conflicts and manual release |
| Active hold for conversion | Held primary + competing pending request | Atomic conversion and self-conflict exclusion |
| Released and converted history | One approved booking, two retained attempts: released and converted | Status/history display; event still in Planning |
| Live expiry | Held primary + competing pending request | Automatic expiry, coordinator notification and freed availability |

The competing request is submitted **before** its primary hold is placed. This
specifically demonstrates that an older request cannot bypass a later hold at
approval time. Use either release **or** competitor approval as directed below;
approving the competitor consumes the free slot and changes subsequent results.

## 1. Live expiry — test this first

1. As `vs1`, open **Live expiry → Primary** using its printed staff route. Under
   **Tentative venue hold**, expect **Active**, expiry, and occupied 09:30–12:45.
   **Approve Booking** is disabled; release/convert actions are available.
2. Open **Live expiry → Competing request** in a second tab. Click **Approve
   Booking**, then **Confirm approval**. Expect an active-hold conflict; the
   competing booking stays pending and the original hold remains unchanged.
3. Open `/venue-staff/availability-calendar`, select the **Live expiry** venue
   and navigate to the printed booking month. The primary appears as one amber
   **Tentative Hold · Reserved period**, spanning 09:30–12:45. Select it to see
   its expiry/status and booking-details link. The other request is a
   **Pending request**, not a reservation.
4. Keep the calendar open across the printed expiry. The amber occupancy must
   disappear without clicking Refresh. Pending-request entries may remain:
   **expiry frees the reservation; it does not delete/cancel the request**.
5. Reopen primary details: expect **Expired**, no release/convert action and a
   still-pending booking. It must never become approved merely by expiring.
6. As `ec1`, check the notification bell. Within approximately the configured
   cleanup interval (30 seconds by default), expect one hold-expired notification
   linking to this event's venue-booking page. Open the event's **Change log**:
   expect an automatic expiry entry attributed to **ConnectSphere/system**.
7. Refresh the competitor's details (**Refresh booking details** after a failed
   approval, or reload), then approve it. Approval should now succeed. The
   expired primary remains pending; the competitor supplies confirmed occupancy.
8. Refresh notifications again after another cleanup interval. The same hold
   must not produce duplicate expiry notifications.

Availability stops blocking at the exact deadline, independently of whether the
worker has yet persisted expiry or sent the notification. The time delay in step
6 concerns background processing only.

## 2. Create, validate and manually release

1. Open **Create and release → Primary** and click **Put on Tentative Hold** beside Approve/Reject. Venue/event and start/end come from
   this existing booking. Under **Tentative venue hold**, enter an expiry later
   than the current time in **Singapore time**, then click **Place tentative
   hold**. Expect success and **Active**; booking status stays pending.
2. Before creating, try an empty/past expiry: the UI must reject it. The API also
   rejects missing expiry or an expiry at/before server time (422). The server is
   authoritative if the workstation clock is inaccurate.
3. In the competing request tab, approval must fail while the hold is active.
   A second overlapping hold on that competing request must also fail without
   recording a new hold or changing either booking.
4. Return to primary and click **Release Hold**. Expect **Released** in history,
   a pending booking and no amber blocking entry after calendar refresh/focus.
   The competing request can now be approved after refreshing its details.
5. Repeating a release/convert API request against the released hold returns
   409. The UI removes these ineligible actions. The original record is retained.

Optional buffer check: while held, editing its setup/turnaround is rejected with
an instruction to release first. A proposed competitor occupying 12:30–13:00
conflicts with turnaround even though event time ends at 12:00. A complete
occupied interval starting exactly at 12:45 is adjacent and is permitted; account
for the competitor's own setup when selecting its requested start.

## 3. Convert into the existing venue booking

1. Open **Active hold for conversion → Primary**. Note its booking ID and the
   current event's Planning status.
2. Click **Convert Hold to Approved Booking**. Expect booking **approved**, hold
   **Converted**, and no remaining release/convert action for that attempt.
3. Reopen calendar: the amber hold is replaced by the confirmed booking's event,
   setup and turnaround entries. They occupy the same 09:30–12:45 period.
4. Try approving this scenario's competing request. It must still fail, now due
   to the approved booking rather than an active hold.
5. As `ec1`, reopen the printed venue-booking page: exactly one booking exists
   for the primary event, with the original booking ID and converted attempt.
   The event remains **Planning**; conversion does not bypass safety review or
   perform coordinator event confirmation. The Change log records conversion.
6. Double conversion or conversion of an expired/released attempt is rejected
   (409); no duplicate booking is created. Rollback and simultaneous-operation
   cases are covered by the PostgreSQL tests below rather than timed clicks.

## 4. Inspect ready-made history

Open **Released and converted history → Primary**. Expect an approved booking
with **Released** and **Converted** attempts, both with original occupied periods
and expiry timestamps. The calendar contains confirmed occupancy only. `ec1`
can inspect both attempts and the creation/release/conversion Change log. After
scenario 1 expires, that separate booking supplies a real **Expired** example.

## 5. Expiry reminder (EC-C3)

With the default 24-hour lead, every seeded hold already expires within the
window, so it is reminded at the next run. As `ec1`, within about 30 seconds of
seeding, expect one **Tentative venue hold expiring soon for "…"** notification
per active hold. Its text names the event, the venue and the expiry in Singapore
time; clicking it opens the event's venue booking page, where the hold shows its
expiry and an **Expiring soon** badge. Waiting longer must not produce a second
reminder for the same hold. When it expires, expect the separate expiry notice
(scenario 1, step 6) and the hold to show **Expired**, never approved.

To see a hold wait until it enters the window, start the backend with a short
lead and place a hold that expires later than that:

```powershell
$env:VENUE_HOLDS_REMINDER_LEAD = 'PT2M'
docker compose up -d --build backend
./backend/dev/seed/vsc3-seed.ps1 -LocalDev -ExpiryMinutes 5
```

For the **Live expiry** hold, expect no reminder and no badge for the first three minutes, then one reminder
within about 30 seconds of the hold entering its last two minutes. Remove the
variable (`Remove-Item Env:VENUE_HOLDS_REMINDER_LEAD`) and rebuild to return to
the 24-hour default. To check reassignment, have `ecl1` reassign the event to
`ec2` before the hold enters the window: only `ec2` is reminded.

## Automated checks

Run tests against a disposable/local test database, not a shared dataset. For
example, use a separately created `csphere_vsc3_test` database on the Compose DB:

```powershell
docker compose exec -T db createdb -U postgres csphere_vsc3_test
docker compose run --rm -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/csphere_vsc3_test backend-test test '-Dtest=VenueHoldFlowTest,VenueHoldReminderFlowTest,VenueBookingApprovalTest,VenueBookingRequestFlowTest,VenueUnavailabilityTest,VenueTimingWorkflowTest,ActivityServiceTest'
npm --prefix frontend test -- src/features/venueStaff/VenueHoldPanel.test.tsx src/features/venueStaff/HoldStatus.test.tsx src/features/venueStaff/VenueSchedulePage.test.tsx
```

Create the test database only once; an already-existing-database error simply
means that step is not needed again. For JDK 25 on Windows, the equivalent Maven
command from `backend/` is:

```powershell
./mvnw.cmd test '-Dspring.datasource.url=jdbc:postgresql://localhost:5432/csphere_vsc3_test' '-Dtest=VenueHoldFlowTest'
```

The 18 hold integration tests cover exact expiry, buffers, touching intervals,
confirmed/held conflicts, release/re-holding, conversion, cancellation/rejection,
operating hours, invalid states, notification retry/deduplication, RBAC and
concurrency. Frontend tests cover validation, retained input, API errors, actions,
calendar distinctions and expiry with an inaccurate browser clock.

Browser journeys require a running **updated** backend and built frontend. See
[browser setup](../frontend/e2e/README.md), then from `frontend/` run:

```powershell
npm run test:e2e -- e2e/tentative-holds.spec.ts e2e/golden-path.spec.ts
```

If local `node_modules/.bin` is missing the executable shortcut while the package
is installed, use `node node_modules/@playwright/test/cli.js test` with those same
two file arguments. The hold journey exercises create → calendar → release →
re-hold → convert. The golden path checks the existing safety/event workflow.

## Refreshing and retaining the demo

After expiry, the same seed batch intentionally stays expired. For a fresh
countdown run the command again with a new default/explicit batch name. Seed
execution creates new marked fixtures; it never resets bookings, extends old
deadlines or deletes history. Existing demo venues can coexist with VS16/VS-C2
seeds because every batch owns newly generated venue IDs.

There is no automatic cleanup script: the real APIs create append-only activity
history, which must not be deleted or bypassed for a reset. Retain these small
labelled batches, or discard a disposable demo database when no longer needed.
An interrupted setup leaves its created IDs in a manifest marked `incomplete`;
choose a new batch for another attempt. Do not remove unrelated application data.

See [implementation, APIs and acceptance mapping](tentative-venue-holds.md).

## Seeder verification — 18 October 2026

Verified against the isolated `csphere_vsc3_test` database through a packaged
backend on port 18083:

- Missing `-LocalDev` and a non-loopback URL both refused before creating data.
- A complete batch created four venues/seven request-event-booking arrangements,
  including both active holds and the released/converted history fixture.
- The generated create/release scenario blocked the older competing request
  while held, then allowed approval after manual release.
- The generated conversion scenario rejected its competitor and converted the
  existing booking without duplication or changing the event's Planning status.
- The history fixture retained both hold attempts and real lifecycle activities.
- A one-minute live hold expired through the real scheduler, sent exactly one
  notification to `ec1`, rejected conversion and stopped blocking its competitor.
- An explicit duplicate batch name refused without resetting manual edits.
- The local development backend was rebuilt with V29 and populated with
  `showcase-20261018` (10-minute live expiry). A read-only check confirmed four
  venues, seven bookings, two active holds, released/converted history, calendar
  occupancy and coordinator activity history. Its manifest is local under
  `logs/vsc3/showcase-20261018.json`; rerun with a new batch for fresh deadlines.
- PowerShell parser validation and `git diff --check` passed. No backend/frontend
  product code changed for this follow-up, so the full application test suites
  were not repeated; their prior results remain in the implementation document.
