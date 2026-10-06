# VS15 — Venue availability calendar

The Venue Staff availability route now shows a read-only month calendar in Asia/Singapore, with venue selection, previous/next month, Today, refresh, a type legend and entry details in a modal dialog. Every overlapping entry remains individually selectable; multi-day entries appear on each occupied date. Narrow screens can scroll the calendar horizontally.

The route is `/venue-staff/availability-calendar`. The venue selector reuses the shared `.field` dropdown styling used in Booking Approval. Navigation and refresh use compact buttons. The month heading sits directly above a two-column row containing the legend on the left and Refresh schedule on the right.

Approved bookings display as Confirmed booking. Pending requests display as Tentative hold and remain non-reserving. Each booking produces separate Event, Setup and Turnaround entries, with zero-length buffers omitted. Details retain the full selected interval and original event timing. Unavailability details include the recorded reason. No mutation actions are available in this calendar.

## Schedule API

`GET /api/venues/{venueId}/schedule?start=<offset timestamp>&end=<offset timestamp>` returns the existing schedule response shape and `Cache-Control: no-store`. Supply both bounds, with end later than start and no more than 93 days apart. Encode the plus sign in timezone offsets (the frontend uses URLSearchParams). Invalid ranges return 422 and malformed timestamps return 400. Existing Venue Staff role restrictions apply.

The frontend requests the entire visible calendar grid, including adjacent-month dates. Queries include intervals where start < range end and end > range start; touching boundaries do not overlap. Booking retrieval considers the full buffered interval, then the frontend displays event/setup/turnaround separately. Calendar reads include completed events with pending or approved bookings, but exclude cancelled events and changed/rejected/cancelled bookings. Calls without bounds retain the previous active-only behaviour, and operational conflict checks are unchanged.

Navigation and refresh retrieve current stored data. Old entries and details disappear while reloading or after failure. Late responses for a previous venue/month are ignored. There is an explicit empty state and retry action. No polling is introduced.

Month navigation, Today, venue changes and refresh update the calendar in place. Before an update, the calendar frame preserves its current height so replacing the grid with loading, empty or error content does not collapse the page and move the scroll position upward. This reserved space lasts for the current page visit; a shorter month can therefore leave blank space below the grid. Refresh remains in the legend row and is disabled while loading. Clicking Today when already viewing the current month leaves the view unchanged; use Refresh schedule to reload it.

## Verification

Final automated verification on 7 October 2026: all 250 frontend tests across 31 files passed, including the six calendar tests; all 20 `VenueUnavailabilityTest` PostgreSQL integration tests passed. The production frontend build, ESLint for the calendar source/tests, and `git diff --check` also passed. Backend tests used Java 25. No browser walkthrough was performed in this final verification pass.

Calendar tests cover overlapping types and buffers, read-only details, period navigation, Singapore dates and midnight boundaries, leap/year boundaries, zero-length buffers, stale responses and refresh failures. PostgreSQL integration tests cover completed-event history, status/venue exclusions, buffered range overlap, touching boundaries, invalid/empty ranges, no-store headers, staff access and preservation of operational reads. Existing unavailability integration tests provide regression coverage.

The scroll regression test verifies that all navigation controls, venue selection and refresh preserve the calendar frame height through loading, successful retrieval and errors, while stale entries remain absent. This is a DOM-level check; it does not measure an actual browser's scroll offset.

Manual acceptance check:

1. Select a venue with approved and pending bookings, buffers and unavailable periods. Verify the legend, separate timing entries and read-only details.
2. Navigate to a past month with a completed event and confirm its approved booking remains visible.
3. Scroll down, then use Previous month, Next month, Today, Refresh schedule and another venue. Confirm the page does not jump upward and the compact controls retain their positions.
4. Interrupt schedule retrieval and refresh. Confirm old entries disappear, an error and retry action appear, and the page retains its height. Restore connectivity and retry.
5. Check a narrow viewport: the legend and refresh occupy their two columns, the venue selector stays within the page, and the calendar scrolls horizontally.

Historical buffers are derived from the venue's stored setup/turnaround settings, as in the existing schedule API; the current schema does not snapshot buffer settings per booking.
