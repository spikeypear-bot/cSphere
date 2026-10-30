# AT08 implementation review — 29 October 2026

Reviewed the current uncommitted implementation on
`feat/at03-registration-confirmation` against the supplied AT08 requirements,
the existing AT01–AT04 contracts, migrations and automated tests.

**Resolution:** the P2 finding below was fixed by the subsequent
[attendee workflow audit](attendee-workflow-audit.md) on 29 October 2026.
Reconciliation now uses `useAttendeeResource` and permanent regressions cover
both delayed responses and delayed errors. The original findings and evidence
below are retained as review history.

## Original finding: P2 — delayed reconciliation could restore stale registration state

At the time of review, `WithdrawRegistrationAction.readCurrent()` performed a
GET outside the request generation tracking in `useRegistrations`. Its result
was unconditionally passed to `onUpdated`. Route guards prevented an old action
changing a different route but did not compare two reads for the same registration.

Reproduction:

1. Open an owned confirmed registration and confirm withdrawal.
2. Lose the POST response while the server is still completing the transaction.
3. The automatic reconciliation GET observes the old confirmed row; delay its
   response in transit.
4. After withdrawal commits, a focus/poll GET returns the newer withdrawn row.
   The page correctly shows Withdrawn and its date.
5. Release the older reconciliation response. The page returns to Registration
   Confirmed and can offer Withdraw Registration again until another refresh.

A focused component reproducer failed at the assertion that Withdrawn remains
visible after step 5. The temporary test was removed from the source tree after
the review; the local diagnostic source/log are retained under ignored
`logs/at08/review-reconciliation-reproducer.tsx` and
`logs/at08/review-reconciliation.log`.

Impact is stale presentation and a possible extra rejected request. The backend
still rejects an already-withdrawn registration under locks, so this does not
release a second place, change the original withdrawal time or enqueue a second
receipt. At that stage, the supplied stale-state acceptance criterion was not
fully satisfied by the UI. The subsequent audit corrected this ordering.

Recommended correction: coordinate reconciliation reads with the same per-route
request revision as ordinary reads, or reject a reconciliation result when a
newer read/mutation has already been applied. Add a permanent regression covering
the ordering above. Preserve both the route-change guard and backend idempotency.
Application code was not changed during the original documentation-only review;
the subsequent authorised workflow audit implemented the correction and tests.

## Server-side review

No additional server-side defect was identified in the reviewed paths:

- The attendee filter-chain rule and token-scoped queries prevent another role
  or attendee selecting the target owner. Missing and foreign registrations
  return the same 404 before event information is read.
- The scalar ownership lookup avoids caching a registration before acquiring
  the event lock. The command locks event then registration, rechecks status/time
  and retains the original reference, registration date and row.
- Capacity is the confirmed-row count, so one successful transition releases
  one place without a separate mutable counter.
- Withdrawal and notification intent share one transaction. The receipt is a
  permitted self-notice with a stable type/registration identity; after-commit
  delivery and the existing worker preserve saved withdrawal on delivery failure.
- V56 preserves unknown legacy dates. V57 extends the live notification shape
  rather than replacing other stories' cases. Existing migrations are unchanged.

## Verification and limits

The existing completion evidence records 160 selected backend tests, 102
attendee/notification component tests, four Chromium journeys, builds/lint and
traceability. Those historical passing checks do not cover the finding above.
The review's additional race reproducer failed before the correction. It is
now represented by a passing permanent regression; broader audit results are
recorded in the [workflow audit](attendee-workflow-audit.md).

During this review, the existing attendee/notification component suite was
rerun: all 102 tests passed. The separate focused race reproducer failed as
described above. Documentation links, whitespace and story traceability were
checked; no application code or checked-in tests were changed. Backend/browser
completion counts above were not rerun during this documentation review.

Event cancellation/rescheduling versus withdrawal share the event lock in the
reviewed code, but the AT08 tests do not directly exercise those concurrent
interleavings. Add such integration coverage when strengthening conflict tests.

The model closes new registration at event start; an independent earlier close
cannot currently be exercised as a distinct persisted scenario. Withdrawal
correctly ignores registration opening/closing settings and checks event start.

AT07/AT09 remain independent. Coordinate V56/V57 numbering and preserve the
withdrawal receipt shape when integrating teammates' migrations. CI, human
review and backlog/story-ID reconciliation remain team integration steps.
