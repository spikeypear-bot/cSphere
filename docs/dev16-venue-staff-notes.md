# DEV16 — Venue Staff presentation and venue notes

Subsequent verification is recorded in the
[Venue Staff coverage audit](venue-staff-coverage-audit.md). Counts below describe
the earlier presentation/notes verification.

User-authorised follow-up, 31 October 2026: make Venue Staff copy and the seeded
venue information suitable for staff using the application, and align the map
with the existing staff pages.

## Presentation review

Reviewed the staff console, catalogue/detail, create/edit form, approval queue,
booking details, suitability/requirement reviews, holds, unavailability/settings,
operational issues and availability calendar, together with the new map and its
selected-venue/event panel. Existing operational guidance was retained where it
already explains a staff action or booking consequence.

Changes:

- The map uses the shared `PageHeader`, `Card` and `feature-skeleton__body` styles.
  Its section gap is the same 1.25rem as the staff page; list gaps are 1rem, matching
  other booking lists. Extra map-only top margin, separator and nested detail-card
  border/padding were removed. The original 900px shell/margins remain.
- Selected venue information reuses `VenueDetailsPanel`, including its radius,
  padding, colours and shadow. A read-only title/actions option avoids duplicate
  venue headings and internal venue IDs in the map view. Booking rows use `Card`.
- The minimum-capacity placeholder is `e.g. 100`; its value remains empty until the
  user enters a filter. Venue Staff placeholders use `--ink-faint`, with entered
  values retaining their normal colour. The empty date field uses the same grey.
- A single-venue marker displays `1`, while shared-location markers show their
  venue count. Pinch, wheel, keyboard and catalogue selection remain available.
- Map copy now says “Explore venues”, “Refresh”, “Loading venues”, “Events at this
  venue” and “No upcoming bookings for this venue”. Algorithm-oriented wording
  about distinct counts and grouped markers was replaced with ordinary staff copy.
- Catalogue/form descriptions, layout-review guidance, setup defaults and booking
  list/release messages were shortened and addressed to the staff member. Existing
  booking rules, approval actions and API contracts were preserved.

## Seeded notes correction

All 20 supplied venue notes were reviewed. The original `venues.json`, V60, V61 and
V62 remain unchanged. `backend/dev/seed/venue_staff_notes.json` records each original
note alongside its staff-facing replacement; no venue or event is duplicated.

V64 changes **only** `venues.additional_information` when the current note still
equals its original supplied text and a unique original room identity matches.
Matching includes normalised name/address and retained legacy UUIDs. Staff-edited,
blank or null notes are preserved. Renamed/readdressed rooms, ambiguous duplicate
identities and missing rooms are skipped. No capacity, layout, accessibility,
facility, hours, coordinates, ID, event or booking field is changed.

The new notes replace phrases such as “test capacity”, “seed cap”, “the app uses
one capacity”, and “not modelled here” with venue-planning information. They retain
operator-verification caveats: EXPO/Sands planning limits remain illustrative,
Suntec's limit remains unverified, and unrecorded accessibility is still unrecorded.
No operator certification or new capacity fact is inferred by this wording change.

`generate_venue_staff_notes.py --check` verifies the review JSON and SQL agree with
the original identities/text and reviewed replacements. After V64 is applied, use
`--check` only. Further corrections need a new migration.

## Application and verification

Rebuild/restart the backend to apply the note correction with Flyway. Do not reset
the database or rerun V61. Existing staff-edited notes are not replaced. V63 was
already reserved by `origin/fix/at09-attendee-notices` when all refs were refreshed
and inspected, so this follow-up uses V64. Integrate branch migrations in version
order before applying to a shared environment.

Verification uses an isolated PostgreSQL database, not the application database.
`VenueStaffNotesMigrationTest` covers all 20 replacements, all other venue fields,
hours/events/bookings snapshots, staff edits/empty/null notes, legacy IDs, changed
identities, ambiguous/missing rooms, full transaction rollback, explicit repeat
execution and normal Flyway restart. `VenueSeedApiTest` verifies current persisted
staff notes alongside every other original venue field through authenticated reads.

Browser checks compare actual computed placeholder/input/card styles with the
existing staff forms and catalogue, check section spacing and marker `1`, exercise
desktop/mobile selected-booking displays and retain pinch/keyboard/tile-error checks.
The existing real Venue Staff workflow also covers catalogue/create/edit, operational
issues, booking assessment/details and calendar presentation.

### Executed results — 31 October 2026

| Check | Result |
|---|---|
| Full frontend regression suite | 95 files, **946 tests passed** |
| Focused Venue Staff + map checks | 22 files, **264 tests passed** |
| Note migration, seed/coordinate preservation, venue/map APIs and diagram checks | **128 backend tests passed**, packaged backend built |
| Desktop/mobile style, placeholder, marker and pinch checks | **3 browser tests passed** |
| Persisted map and existing staff catalogue/edit/booking/calendar journey | **3 browser tests passed** |
| Unavailability release/history/availability journey | **1 browser test passed** |
| Frontend build and lint | Passed; existing main-bundle size advisory remains |
| Traceability | 252 files, 79 stories, zero untraced files |
| Original venue/coordinate generators and staff-note generator `--check` | Passed; original JSON and V60–V62 unchanged |

Visual review used real map tiles and persisted notes/bookings in the isolated test
database, with desktop and mobile images in `logs/dev16-staff-polish-*.png`. The final
regression also verifies the revised unavailable-period release confirmation after
correcting a success-message assignment caught during testing. No existing assertion
or booking-state guard was removed. The temporary servers/database were stopped.

These are local results, not CI results. No commit/push or application database change
was made. Rebuild/restart the updated backend to apply the note correction normally.
Test logs use the `logs/dev16-staff-polish-*` prefix.
