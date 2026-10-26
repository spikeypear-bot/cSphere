# Venue Staff multiple-venue audit fixes — 26 October 2026

Scope: F10, F6, F9 and F7 only. No EO, EC, ECL, SO, TSS or Attendee workflow changes in this follow-up. No readiness/safety, ownership, schema, release or history changes.

- F10: approval computes current effective accessibility after acquiring the existing event/venue locks. Missing accessibility requires the existing nonblank coordinator suitability justification. An explicit empty accessibility override remains empty; absent overrides inherit the event. This check runs before replacement release or hold conversion.
- F6/F9: the existing staff `VenueBookingDto` adds `effectiveRequirements`, using the existing BookingRequirements record. Non-null booking fields override event fields, including empty lists and empty written text. `VenueEvent` now reads required facilities. Both queue and detail display resolved attendance and written requirements; detail also displays inherited or overridden facilities.
- F7: the existing detail response adds `relatedBookings`, containing only other bookings for the same event, with IDs, venue, status, booking window and occupied window. The summary includes retained historical bookings. List responses omit this additional lookup and return an empty collection. Details show event name, event ID and selected booking ID. Related summaries have no action controls. Existing selected-booking approval/rejection paths are unchanged.
- Shared Venue Staff access and existing private-booking role rules remain unchanged. No new API endpoint or permission rule is introduced.

## Files changed in this follow-up

Backend:
- `venuebooking/service/VenueBookingService.java`
- `venuebooking/dto/VenueBookingDto.java`
- `venuebooking/entity/VenueEvent.java`
- `venuebooking/repository/VenueBookingRepository.java`
- `src/test/java/com/example/connect_sphere/venuebooking/VenueBookingApprovalTest.java`

Frontend:
- `src/types/venueBooking.ts`
- `src/features/venueStaff/BookingApprovalsPage.tsx`
- `src/features/venueStaff/BookingDetailsPage.tsx`
- `src/features/venueStaff/EventRequirementsPanel.tsx`
- `src/features/venueStaff/BookingApprovalsPage.test.tsx`
- `src/features/venueStaff/BookingDetailsPage.test.tsx`

This document records the scoped follow-up; earlier working-tree changes are preserved.

## Verification

- Frontend: 81 tests passed across approval list, booking details and timing editor. Build and lint passed; Vite retains its bundle-size advisory.
- Story traceability: zero untraced files. `git diff --check` passed.
- Backend: all 123 targeted tests passed (zero failures/errors). Targeted backend suites cover approval (including concurrency), reads/role access, rejection, timing, tentative holds and multiple-venue preservation. Results are recorded in `logs/vs-review-20261025/vs-focused-backend.log`.
- Browser E2E and the full application suite are outside this targeted run. No production migration, commit or push is performed.