# Venue Staff presentation audit (25 October 2026)

This audit covers the implemented staff home, catalogue, create/edit forms, venue
and booking details, approval queue, holds, requirement reviews, suitability
reassessment, timing conflicts, availability calendar, unavailability and
operational issues, plus the shared notification bell. It does not add a new
business workflow or reopen the paused completed-review editing request.

## Context checked

Read `AGENTS.md`, `AI_Context.md` (including the test checklist), product context,
implementation roadmap, decision log and testing strategy, then the venue API,
booking, requirement review, operating-hour, calendar and unavailability guides.
The checkout contains `docs/` and `AI_Context.md`; a separate Context or Materials
folder is absent. The user-confirmed VS17 story identifier remains canonical for
this work; exact live backlog AC numbering and external source files were not
available. No AC numbers have been invented.

## Changes and evidence

| Area | Finding and correction | Automated evidence |
| --- | --- | --- |
| Staff home | Removed the unbuilt-page warning, Skeleton badges, story IDs and backend-package guidance; retained the four existing feature links | `VenueStaffPresentation.test.tsx`, `App.test.tsx`, staff browser walkthrough |
| Booking statuses | Use shared human labels in associated bookings, event requirements, conflict cards and unavailability previews, including Replaced and Requires attention; accessible labels match visible labels | `BookingDetailsPage.test.tsx`, `BookingApprovalsPage.test.tsx`, `VenueStaffPresentation.test.tsx`, `VenueUnavailabilityPanel.test.tsx` |
| Live associated bookings | Venue details refresh their booking statuses on focus and every five seconds; active holds use the existing hold deadline logic | `VenueStaffPresentation.test.tsx` and existing hold/booking tests |
| Calendar | Attention bookings retain event/setup/turnaround entries, conflict markers and booking links; terminal/replaced bookings remain excluded | `VenueSchedulePage.test.tsx`, staff browser walkthrough |
| Colours and case | Shared attention tokens make badges and calendar entries red independently of event-page styles; status text keeps its defined capitalization | `bookingStatusColours.test.ts`, browser attention-calendar check |
| Review forms and headings | Reassessment uses the same paper fields and heading scale as existing requirement review/booking sections; history remains available | `BookingAssessment.test.tsx`, desktop/mobile browser screenshots and field-background check |
| Notification text | Requirement-change, review-outcome, suitability and arrangement-change notifications show their header and timestamp; full generated descriptions remain stored and linked details remain available | `VenueRequirementNotification.test.tsx`, requirement-review browser journey |
| Duplicate/misleading text | An attention booking uses its suitability assessment instead of also claiming the cause is venue unavailability; concise review guidance replaces infrastructure explanations | Staff browser walkthrough, existing requirement-review tests |
| Loading failure | Null, object or malformed review lists show a retryable error rather than crashing booking details or claiming no reviews | `VenueRequirementReviews.test.tsx` |
| Operational issues | Clearly label Singapore time and convert entered wall-clock times with an explicit +08:00 offset; preserve staff-written descriptions and input after failure | `VenueOperationalIssuesPanel.test.tsx`, real API/browser issue submission |
| Scroll/focus | Scroll fixture returns each endpoint's actual response shape; booking pages retain their document height across disappearing hold panels, preserving the desktop/mobile editor and scroll position | `booking-scroll.spec.ts` |

Staff-entered requirements, reasons, comments and audit history are preserved.
Useful validation, confirmation and safety warnings remain. All view-only changes
leave reservation and booking decision rules in the existing services.

## Verification and limits

The initial staff/notification baseline had 24 failures in the legacy venue form
and return-navigation files. The user subsequently assigned those updates to this
chat. Both files now use structured-hour fixtures and controls, preserving all
navigation, validation and failure assertions; no test-file exclusions remain.
Additional backend booking, hold, persistence and issue fixtures now supply valid
weekly hours through the shared test helper. Free-text validation expectations
have been replaced with structured-hour validation, rather than bypassing the
new required schedule.

Final verification: **675 frontend tests across 65 files**, **1,374 backend tests**,
and **8 Chromium browser journeys** passed with no failures, skipped tests or
file exclusions. The browser journeys cover the full approval/confirmation path,
hold release/conversion, requirement review and coordinator visibility, live
structured hours across EO/EC/VS, unsuitable venue edits and explicit recovery,
creation/issue/calendar presentation, and desktop/mobile scroll preservation.

Frontend build, lint, backend packaging and whitespace checks passed. Traceability
reported **164 test files, 61 stories traced, 0 untraced files**; its seven unrelated
high-priority story gaps remain visible in the existing traceability report.
Browser/integration checks used a temporary native PostgreSQL 18 database and
separate backend/frontend ports while Docker was unavailable. Test services were
stopped afterward; the user's application data was not changed.

The booking page retains its maximum document height during a visit to avoid
scroll clamping when a hold panel disappears. This can leave space at the bottom
of the page, rather than between Venue Information and its detail cards.

## Completion re-audit

The follow-up review found and closed these remaining gaps:

- Removed `operatingInformation` from the editable frontend input type and form
  state. It remains only a compatibility property of API response types; creation
  and update requests contain structured hours.
- Extended real browser journeys to edit and save hours from catalogue cards,
  venue details and booking details, checking return URLs, the structured request
  payload, and live EC/EO updates. Earlier browser hours edits used API calls.
- Updated the manual VS16 seed to create five structured weekday intervals for
  newly created demo venues. Existing venues and edited schedules remain intact.
  Verified with real PostgreSQL: missing opt-in is refused without data changes;
  initial seed creates Monday-Friday 09:00-18:00; a repeat preserves changed
  capacity, hours and booking rows; guarded cleanup succeeds. Removed the unused
  legacy field from the API-backed VS-C3 seed and checked its PowerShell syntax.
- Corrected the old other-chat ownership/failing-fixture paragraph in the
  requirement-review guide, marked old free-text decisions as superseded, clarified
  that retained database prose is not displayed, and regenerated the traceability
  report. Regenerated class/ER diagrams are retained with the implementation.

Fresh full-suite verification passed **675 frontend tests, 1,374 backend tests
and all 8 Chromium browser journeys**, without skips or exclusions. The browser
run includes the strengthened edit flows. Build, lint, traceability and whitespace
checks passed. Execution logs: `staff-completion-ui.log`,
`staff-completion-backend.log`, and `staff-completion-browser.log` (ignored local
artifacts). The manual seed dates remain historical read and
pagination fixtures; use future events for approval/availability exercises.

## Manual check

1. Open Venue Staff home: four usable feature cards, without developer scaffolding.
2. Create/edit a venue with structured days and times; inspect the catalogue and
   venue/booking details. Follow Edit venue from each entry point and check return
   navigation. Missing days and invalid ranges should prevent saving.
3. Approve/reject a request, create/release/convert a hold, and inspect its history.
   Only the selected confirmation opens; completed decisions do not leave a hold form.
4. Reduce a booked venue's capacity below attendance. Check the assessment, associated
   booking label and red calendar entries, including saved setup/turnaround. Confirm
   reservation times are unchanged and restore approval only after issues are resolved.
5. Open a requirement-change notification, record an outcome and check coordinator
   visibility. Reading a notification does not decide the review. Interrupt reads or
   saves and use retry; comments should be retained where appropriate.
6. Preview unavailability and return without saving; confirm explicitly to persist.
   Report an operational issue with an affected interval, including from a browser in
   another timezone, and confirm displayed/saved times remain Singapore time.
7. Repeat the booking and calendar checks at 390px width; only the calendar grid
   should require horizontal scrolling. Expand the timing editor and confirm focus
   refresh and hold expiry preserve the editor and scroll position.
