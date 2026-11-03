# Release 1 cross-role integration repairs

Scope authorised on 3 November 2026: EO03, EC-NEW3, EC07, EC08, TS01/TS02,
SO-C1 and AT01/AT02, with the existing registration-management contracts retained.
No commit, push, merge, database reset or historical migration edit is included.

## Sources and rules

The supplied repair brief, `AI_Context.md`, `product-context.md`, decision-log
D29/D36/D37/D34, existing services and their regression tests define this repair.
The live backlog could not be retrieved in this environment; story IDs were
explicitly confirmed by the user. This document does not claim a backlog update.

Expected attendance is a planning figure. Registration capacity independently
limits registration. No documented equality or upper-bound relationship was
established, so the repair does not introduce one. An attendance proposal that
keeps the existing capacity needs explicit EO confirmation (`capacityConfirmed`);
changing the capacity supplies that explicit choice. Positive capacity and the
current confirmed-registration floor are enforced by the backend. The floor is
checked at submission and again under the event lock at approval. Registration
cannot be disabled while confirmed registrations exist.

## Authoritative data flow

EC08/SO-C1 follow-up (3 November 2026): the Safety Officer home page now refreshes
its queue every five seconds, matching the Safety reviews page. The SO notification
bell also polls every five seconds. Withdrawal creates a durable
`safety_review_withdrawn` notice with the coordinator's reason (V72), linked to the
review list. Withdrawn events leave the waiting-for-review queue and return to the
existing In planning section; they are not deleted. Failed or repeated withdrawals
do not create a new notice. Submission notices retain their event-detail links.

1. EO proposes changes in `event_change_requests`; existing event details and
   equipment reservations remain unchanged while pending.
2. ECL assignment and existing EC ownership checks remain authoritative.
3. EC approval locks the change, originating request and event. Validation,
   approved EventRequest/Event fields, history, venue reassessment and equipment
   attention remain atomic. Arrangement changes retain the existing Planning /
   safety / reconfirmation flow.
4. Structured requirements use the existing EC07 catalogue item shape:
   `equipmentItems: [{equipmentId, quantity}]`. V69 stores approved selections
   as JSONB on EventRequest and Event; immutable change snapshots record the
   proposed IDs and quantities. Free-text `equipmentRequirements` remains
   supported, and is never parsed into catalogue items. Omitted structured items
   preserve existing selections for legacy clients; an explicit empty list
   clears them. IDs are checked against the catalogue; duplicates, null items,
   nonpositive quantities and fractional JSON quantities are refused.
5. Requirements are separate from operational EC request lines. An approved
   structured change invokes the existing equipment-attention and TS change-review
   mechanisms. EC explicitly revises the request using D36: preserve old request
   rows/lines, supersede them, release event reservations and create a new
   processing request transactionally. TS reviews that new request and its actual
   quantities; nothing is automatically approved. Existing stock, spare and
   reservation-coverage checks remain in place. Structured approved quantities
   must also be reserved before an approval or a “still suitable” decision can
   treat the requirements as fulfilled. Free-text suitability remains a human
   judgement. Extra reserved units are permitted by the existing spare rules.
6. EC safety submission creates an SO notice in the existing transactional outbox.
   The event lock and transition guard admit one notice set per review cycle;
   retries retain the outbox identity. A genuine new review cycle creates new
   notices. V70 adds the notification enum and extends the existing database
   shape constraint without replacing its earlier branches.
7. ATT reads approved Event values and confirmed registration counts. Expected
   attendance, registration capacity and available places are labelled separately.
   Available places remains `max(0, approved cap - confirmed registrations)`.

## Root causes and affected implementation

| Area | Cause | Repair locations |
| --- | --- | --- |
| EO registration availability | The write endpoint already gated confirmation, while the organiser summary used dates alone | `event/service/EventRegistrationService.java`; existing attendee eligibility preserved |
| Attendance/capacity | Change validation omitted current registration counts and capacity confirmation | `changerequest/service/ChangeRequestService.java`, DTO/controller and EO change form |
| Registration period propagation | Change snapshots omitted closing time; separate capacity/period updates left EventRequest stale | `ChangeFields.java`, `EventRequestService.java`, registration capacity/period services |
| ATT figures | No expected-attendance field in the attendee-safe details projection; the capacity label could be confused with attendance | `AttendeeEventDetailsDto.java`, `AttendeeEventDetailsService.java`, attendee details page |
| Equipment proposal integration | EO changes had only text; structured EC lines and reservations were separate without structured proposal data | EventRequest/Event DTOs, entities and service; V69; change fields/form; attention/impact services; EC equipment page |
| Structured JSON comparisons | Collection comparisons used map string order rather than structural meaning | `activity/service/ActivityService.java` |
| SO notification | Safety submission recorded activity but did not enqueue an SO notice | `EventLifecycleService.java`, `NotificationService.java`, notification enum, V70 and notification bell description |
| EC/TS/SO refresh | Some reads ran only on mount or explicit reload; background and mutation responses needed ordering | `useRefreshingResource.ts`, EC event/change/equipment pages, TS request/review pages, SO `useSafetyData.ts` |
| TS counts | Distinct types and total units were not separately exposed in the list | `technicalSupport/requests/RequestFacts.tsx`; existing server item lines retained |

`useRefreshingResource` reuses the five-second/focus refresh mechanism. It keeps
the current view during background loads, allows valid slow reads to complete
across polling ticks, rejects older completed responses, and invalidates reads
started before saved mutations. Form drafts remain separate from refreshed
server data. The notification bell retains its established polling/focus and
read-mutation safeguards; attendee reads retain `useAttendeeResource`.

## Validation

Verification used a fixed source copy with the checked-in seed fixtures,
separate PostgreSQL regression/browser databases, Java 25 and the existing npm
installation. The backend verification bounds Spring's test-context cache to four
contexts to stay within the local PostgreSQL connection limit. Application,
validation, CI and coverage settings were retained.

| Check | Actual result |
| --- | --- |
| Full frontend suite, `npm test -- --maxWorkers=2` | 117 files, 1,170 tests passed |
| Final ATT and synchronization cases | 7 tests passed, including approved attendance/capacity 200 -> 150 |
| Frontend build and lint | Both passed; Vite reports the existing large-bundle advisory |
| New cross-role browser tests | 2 passed: EO -> EC -> TS quantity revision, and EC -> SO queue/notice discovery |
| Existing browser regressions | 7 passed: multi-venue golden path, equipment reservation/rejection/fault/damage, capacity/withdrawal and registration periods |
| Traceability | 294 files, 84 stories, zero untraced files |
| Existing test/assertion counts | Six edited existing files checked; no reductions |
| Full backend `mvnw verify -Dspring.test.context.cache.maxSize=4` | 1,989 tests passed, zero failures/errors/skips; packaging and the existing coverage gate passed |

Logs are retained at the repository root under `release1-*`. Earlier attempts
exposed shared build-output interference, omitted snapshot seed fixtures and local
connection exhaustion; those harness issues were corrected for the final run.

Evidence: [backend verification](../release1-backend-verified.log),
[full frontend suite](../release1-isolated-frontend.log),
[final ATT/synchronization cases](../release1-attendee-final.log),
[new browser flows](../release1-browser-repairs.log),
[existing browser regressions](../release1-browser-regression.log),
[build](../release1-isolated-build.log), [lint](../release1-isolated-lint.log),
[traceability](../release1-traceability-final.log), and
[existing assertion-count checks](../release1-test-protection.log).

New tests cover capacity confirmation and the 200 -> 150 scenario, changing
registration counts during pending approval, structured equipment validation
and revision, SO delivery/retry identity and review cycles, automatic discovery,
type/unit counts, and stale/slow read ordering.

## Deployment and manual checks

V69, V70 and V71 are additive and required before running the updated backend.
V71 aligns legacy originating requests with authoritative Event registration
settings; it does not change Event or attendee data. They were applied only to
new local regression and browser databases during this work. Normal Flyway
startup applies them to another environment when that
environment is updated; existing data is retained with empty structured lists.
If an older pending proposal captured divergent registration settings, the existing
stale-proposal guard requires withdrawing and resubmitting it after the backfill;
its original history is retained rather than silently rewritten.

Check the existing free-text equipment fixtures, multi-venue readiness,
EO-to-EC pending indicator, revised TS request items, SO bell deep link and
ATT reconfirmation messaging together in the shared demonstration environment.
The live backlog still needs the team's normal synchronisation.

No remaining implementation defects or verification blockers were identified by
these checks. The shared demo environment was not updated; its normal backend
restart must apply V69–V71 before using the new code. Verification servers were
stopped after the browser tests. No commit, push or merge was performed.

## Changed files

- [AI_Context.md](../AI_Context.md)
- [backend/src/main/java/com/example/connect_sphere/activity/service/ActivityService.java](../backend/src/main/java/com/example/connect_sphere/activity/service/ActivityService.java)
- [backend/src/main/java/com/example/connect_sphere/attendee/dto/AttendeeEventDetailsDto.java](../backend/src/main/java/com/example/connect_sphere/attendee/dto/AttendeeEventDetailsDto.java)
- [backend/src/main/java/com/example/connect_sphere/attendee/service/AttendeeEventDetailsService.java](../backend/src/main/java/com/example/connect_sphere/attendee/service/AttendeeEventDetailsService.java)
- [backend/src/main/java/com/example/connect_sphere/changerequest/controller/ChangeRequestController.java](../backend/src/main/java/com/example/connect_sphere/changerequest/controller/ChangeRequestController.java)
- [backend/src/main/java/com/example/connect_sphere/changerequest/dto/ChangeRequestDto.java](../backend/src/main/java/com/example/connect_sphere/changerequest/dto/ChangeRequestDto.java)
- [backend/src/main/java/com/example/connect_sphere/changerequest/service/ChangeFields.java](../backend/src/main/java/com/example/connect_sphere/changerequest/service/ChangeFields.java)
- [backend/src/main/java/com/example/connect_sphere/changerequest/service/ChangeImpactService.java](../backend/src/main/java/com/example/connect_sphere/changerequest/service/ChangeImpactService.java)
- [backend/src/main/java/com/example/connect_sphere/changerequest/service/ChangeRequestService.java](../backend/src/main/java/com/example/connect_sphere/changerequest/service/ChangeRequestService.java)
- [backend/src/main/java/com/example/connect_sphere/changerequest/service/EquipmentRequestAttentionService.java](../backend/src/main/java/com/example/connect_sphere/changerequest/service/EquipmentRequestAttentionService.java)
- [backend/src/main/java/com/example/connect_sphere/equipmentrequest/service/EquipmentRequestDecisionService.java](../backend/src/main/java/com/example/connect_sphere/equipmentrequest/service/EquipmentRequestDecisionService.java)
- [backend/src/main/java/com/example/connect_sphere/equipmentrequest/service/EquipmentRequestService.java](../backend/src/main/java/com/example/connect_sphere/equipmentrequest/service/EquipmentRequestService.java)
- [backend/src/main/java/com/example/connect_sphere/equipmentrequest/service/EquipmentRequirementCoverage.java](../backend/src/main/java/com/example/connect_sphere/equipmentrequest/service/EquipmentRequirementCoverage.java)
- [backend/src/main/java/com/example/connect_sphere/equipmentreview/dto/EquipmentChangeReviewDto.java](../backend/src/main/java/com/example/connect_sphere/equipmentreview/dto/EquipmentChangeReviewDto.java)
- [backend/src/main/java/com/example/connect_sphere/equipmentreview/service/EquipmentChangeImpact.java](../backend/src/main/java/com/example/connect_sphere/equipmentreview/service/EquipmentChangeImpact.java)
- [backend/src/main/java/com/example/connect_sphere/equipmentreview/service/EquipmentChangeReviewService.java](../backend/src/main/java/com/example/connect_sphere/equipmentreview/service/EquipmentChangeReviewService.java)
- [backend/src/main/java/com/example/connect_sphere/event/dto/EventDto.java](../backend/src/main/java/com/example/connect_sphere/event/dto/EventDto.java)
- [backend/src/main/java/com/example/connect_sphere/event/entity/Event.java](../backend/src/main/java/com/example/connect_sphere/event/entity/Event.java)
- [backend/src/main/java/com/example/connect_sphere/event/service/EventLifecycleService.java](../backend/src/main/java/com/example/connect_sphere/event/service/EventLifecycleService.java)
- [backend/src/main/java/com/example/connect_sphere/event/service/EventRegistrationPeriodService.java](../backend/src/main/java/com/example/connect_sphere/event/service/EventRegistrationPeriodService.java)
- [backend/src/main/java/com/example/connect_sphere/event/service/EventRegistrationService.java](../backend/src/main/java/com/example/connect_sphere/event/service/EventRegistrationService.java)
- [backend/src/main/java/com/example/connect_sphere/event/service/EventService.java](../backend/src/main/java/com/example/connect_sphere/event/service/EventService.java)
- [backend/src/main/java/com/example/connect_sphere/eventrequest/dto/EventRequestDto.java](../backend/src/main/java/com/example/connect_sphere/eventrequest/dto/EventRequestDto.java)
- [backend/src/main/java/com/example/connect_sphere/eventrequest/dto/SaveEventRequestRequest.java](../backend/src/main/java/com/example/connect_sphere/eventrequest/dto/SaveEventRequestRequest.java)
- [backend/src/main/java/com/example/connect_sphere/eventrequest/entity/EventRequest.java](../backend/src/main/java/com/example/connect_sphere/eventrequest/entity/EventRequest.java)
- [backend/src/main/java/com/example/connect_sphere/eventrequest/repository/EventRequestRepository.java](../backend/src/main/java/com/example/connect_sphere/eventrequest/repository/EventRequestRepository.java)
- [backend/src/main/java/com/example/connect_sphere/eventrequest/service/EventRequestService.java](../backend/src/main/java/com/example/connect_sphere/eventrequest/service/EventRequestService.java)
- [backend/src/main/java/com/example/connect_sphere/notification/entity/NotificationType.java](../backend/src/main/java/com/example/connect_sphere/notification/entity/NotificationType.java)
- [backend/src/main/java/com/example/connect_sphere/notification/service/NotificationService.java](../backend/src/main/java/com/example/connect_sphere/notification/service/NotificationService.java)
- [backend/src/main/resources/db/migration/SCHEMA.md](../backend/src/main/resources/db/migration/SCHEMA.md)
- [backend/src/main/resources/db/migration/V69__structured_equipment_requirements.sql](../backend/src/main/resources/db/migration/V69__structured_equipment_requirements.sql)
- [backend/src/main/resources/db/migration/V70__safety_review_requested_notification.sql](../backend/src/main/resources/db/migration/V70__safety_review_requested_notification.sql)
- [backend/src/main/resources/db/migration/V71__align_approved_registration_settings.sql](../backend/src/main/resources/db/migration/V71__align_approved_registration_settings.sql)
- [backend/src/test/java/com/example/connect_sphere/attendee/AttendeeEventChangeNotificationFlowTest.java](../backend/src/test/java/com/example/connect_sphere/attendee/AttendeeEventChangeNotificationFlowTest.java)
- [backend/src/test/java/com/example/connect_sphere/changerequest/ReleaseOneIntegrationRepairTest.java](../backend/src/test/java/com/example/connect_sphere/changerequest/ReleaseOneIntegrationRepairTest.java)
- [backend/src/test/java/com/example/connect_sphere/changerequest/service/ChangeFieldsTest.java](../backend/src/test/java/com/example/connect_sphere/changerequest/service/ChangeFieldsTest.java)
- [backend/src/test/java/com/example/connect_sphere/changerequest/service/RegistrationChangeFieldsTest.java](../backend/src/test/java/com/example/connect_sphere/changerequest/service/RegistrationChangeFieldsTest.java)
- [backend/src/test/java/com/example/connect_sphere/event/EventLifecycleFlowTest.java](../backend/src/test/java/com/example/connect_sphere/event/EventLifecycleFlowTest.java)
- [backend/src/test/java/com/example/connect_sphere/event/service/EventRegistrationPeriodServiceTest.java](../backend/src/test/java/com/example/connect_sphere/event/service/EventRegistrationPeriodServiceTest.java)
- [backend/src/test/java/com/example/connect_sphere/event/service/EventRegistrationServiceTest.java](../backend/src/test/java/com/example/connect_sphere/event/service/EventRegistrationServiceTest.java)
- [backend/src/test/java/com/example/connect_sphere/notification/SafetyReviewNoticeDeliveryTest.java](../backend/src/test/java/com/example/connect_sphere/notification/SafetyReviewNoticeDeliveryTest.java)
- [docs/class-diagrams/domain.generated.puml](../docs/class-diagrams/domain.generated.puml)
- [docs/class-diagrams/full.generated.puml](../docs/class-diagrams/full.generated.puml)
- [docs/decision-log.md](../docs/decision-log.md)
- [docs/er-diagrams/schema.generated.puml](../docs/er-diagrams/schema.generated.puml)
- [docs/release1-integration-repairs.md](../docs/release1-integration-repairs.md)
- [frontend/e2e/release1-integration-repairs.spec.ts](../frontend/e2e/release1-integration-repairs.spec.ts)
- [frontend/src/features/attendee/AttendeeEventDetailsPage.tsx](../frontend/src/features/attendee/AttendeeEventDetailsPage.tsx)
- [frontend/src/features/changeRequests/ChangeRequestFormPage.tsx](../frontend/src/features/changeRequests/ChangeRequestFormPage.tsx)
- [frontend/src/features/changeRequests/ChangeRequestPanel.tsx](../frontend/src/features/changeRequests/ChangeRequestPanel.tsx)
- [frontend/src/features/changeRequests/ChangeRequestReviewPage.tsx](../frontend/src/features/changeRequests/ChangeRequestReviewPage.tsx)
- [frontend/src/features/changeRequests/ChangeRequestsPage.tsx](../frontend/src/features/changeRequests/ChangeRequestsPage.tsx)
- [frontend/src/features/changeRequests/ReleaseOneSynchronization.test.tsx](../frontend/src/features/changeRequests/ReleaseOneSynchronization.test.tsx)
- [frontend/src/features/changeRequests/changeRequestFields.ts](../frontend/src/features/changeRequests/changeRequestFields.ts)
- [frontend/src/features/coordinator/EquipmentRequestPage.tsx](../frontend/src/features/coordinator/EquipmentRequestPage.tsx)
- [frontend/src/features/events/EventDetailsPage.tsx](../frontend/src/features/events/EventDetailsPage.tsx)
- [frontend/src/features/notifications/NotificationBell.tsx](../frontend/src/features/notifications/NotificationBell.tsx)
- [frontend/src/features/organiser/ClarificationResponsePage.tsx](../frontend/src/features/organiser/ClarificationResponsePage.tsx)
- [frontend/src/features/safetyOfficer/useSafetyData.ts](../frontend/src/features/safetyOfficer/useSafetyData.ts)
- [frontend/src/features/technicalSupport/changeReviews/ChangeReviewPage.tsx](../frontend/src/features/technicalSupport/changeReviews/ChangeReviewPage.tsx)
- [frontend/src/features/technicalSupport/changeReviews/ChangeReviewsPage.tsx](../frontend/src/features/technicalSupport/changeReviews/ChangeReviewsPage.tsx)
- [frontend/src/features/technicalSupport/changeReviews/changeReview.types.ts](../frontend/src/features/technicalSupport/changeReviews/changeReview.types.ts)
- [frontend/src/features/technicalSupport/requests/EquipmentRequestDetailPage.tsx](../frontend/src/features/technicalSupport/requests/EquipmentRequestDetailPage.tsx)
- [frontend/src/features/technicalSupport/requests/EquipmentRequestsPage.tsx](../frontend/src/features/technicalSupport/requests/EquipmentRequestsPage.tsx)
- [frontend/src/features/technicalSupport/requests/RequestFacts.tsx](../frontend/src/features/technicalSupport/requests/RequestFacts.tsx)
- [frontend/src/lib/useRefreshingResource.ts](../frontend/src/lib/useRefreshingResource.ts)
- [frontend/src/types/event.ts](../frontend/src/types/event.ts)
- [frontend/src/types/eventRequest.ts](../frontend/src/types/eventRequest.ts)
- [frontend/src/types/notification.ts](../frontend/src/types/notification.ts)
