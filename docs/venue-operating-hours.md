# Venue operating hours across roles

The structured `venue_operating_hours` rows are authoritative for display and booking
checks. `VenueHoursService` supplies a consistent summary and schedule to catalogue,
search, staff booking and coordinator option reads through `VenueMapper`; organiser
arrangements use the same reader. Existing coordinator bookings and organiser arrangements include the structured rows as well as the summary.
Stored descriptive `operating_information` text is not used to advertise opening hours
when a structured schedule is missing. Such records display **Operating hours not configured**.
No hours are guessed from legacy prose, and no legacy data is deleted by these read changes.

All role displays use the shared `OperatingHours` component with one row per configured
day, ordered Monday to Sunday. It formats times as **Monday 9am – 6pm** or
**Tuesday 10:30am – 11:30pm**, preserves meaningful seconds, and shows a small
**Singapore time** caption using the existing venue-card colours and type scale.
No display renders the legacy free-text field as a schedule.

Times are Singapore local time. The availability checks require the full occupied period,
including saved setup and turnaround, to fit a configured daily interval. Unselected days
are closed; overnight intervals remain unsupported. Venue creation requires at least one
configured operating day, as established by the separate operating-hours editor work.

Venue Staff catalogue, venue details and booking details refresh automatically; coordinator
selection, existing-booking cards and organiser arrangements also refresh without navigation.
EO arrangements label the schedule **Operating hours**. EC selected details contain one
schedule display rather than duplicate operating/structured-hours sections. Venue schedule
edits continue through the existing booking reassessment and event lifecycle logic. They do
not move a booking's reserved period or change its saved setup/turnaround durations.

This supersedes older documentation describing `operating_information` as independently
editable descriptive text. The field remains in existing request DTOs for compatibility;
the separate hours-editing workflow derives it from structured rows.

Targeted verification covers canonical schedule order/precision, missing legacy schedules,
real VS catalogue/booking, EC option/existing-booking and EO arrangement API reads, live
role displays and the browser journey after a valid then unsuitable schedule change.

Final verification: 1,374 backend tests, 675 frontend tests and 8 Chromium
journeys passed, without skips or file exclusions. This includes structured-hour
creation/edit validation and return navigation, ignored legacy prose, real
VS/EC/EO API reads, search/comparison/history displays, and live hours on open
EO/EC/VS pages after suitable and unsuitable edits. Existing booking times and
reservation behaviour were verified. Backend packaging, frontend build, lint,
whitespace and traceability checks passed (164 test files, 0 untraced files).
See the [staff audit](venue-staff-presentation-audit.md) for scope and test mapping.

The creation UI sends only structured hours, and venue search no longer reads the
legacy prose field. Compatibility summaries in API responses are derived solely
from structured rows, including plain mapper/service test construction. Legacy
text alone cannot create a venue or overwrite its saved schedule. Historical
prose remains stored for compatibility; it is not parsed or advertised as hours.

Completion re-audit: the editable frontend DTO/form state excludes the legacy
property entirely. Browser checks now edit and save actual hours through all three
staff entry points (catalogue, venue details, booking details), validate return
navigation and structured payloads, and observe live EC/EO schedules. New VS16
demo venues also receive structured weekday rows; repeat seeds preserve existing
edits. See the staff audit for the seed guard/repeatability verification.
