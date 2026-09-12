import type { SkeletonFeature } from '../../types/skeletonFeature'

// Story IDs per docs/product-context.md § End-to-End Workflows (steps 3, 4,
// 5, 6, 7, 9) and § Detailed User Stories — re-check against the live
// backlog sheet before building any of these for real.
export const coordinatorFeatures: SkeletonFeature[] = [
  {
    path: 'review-queue',
    navLabel: 'Review requests',
    pageTitle: 'Event Request Review',
    storyIds: ['EC01', 'EC02'],
    featureArea: 'Event Review and Approval',
    summary: 'Review a submitted event request; approve, reject, or send it back for clarification.',
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.eventrequest (extend with a review/approve action)',
  },
  {
    path: 'venue-search',
    navLabel: 'Search venues',
    pageTitle: 'Venue Search & Suitability',
    storyIds: ['EC04', 'EC05'],
    featureArea: 'Venue Search and Filtering / Venue Suitability Checking',
    summary:
      "Search the venue catalogue against an event's requirements and check suitability against capacity, accessibility, and facilities.",
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.venue',
  },
  {
    path: 'venue-booking',
    navLabel: 'Request a venue booking',
    pageTitle: 'Venue Booking Request',
    storyIds: ['EC03'],
    featureArea: 'Venue Booking Request',
    summary: 'Submit a venue booking request for an event to Venue Staff for approval.',
    layout: 'form',
    suggestedBackendPackage: 'com.example.connect_sphere.venuebooking',
  },
  {
    path: 'equipment-request',
    navLabel: 'Request equipment',
    pageTitle: 'Equipment Request',
    storyIds: ['EC07'],
    featureArea: 'Equipment Request Management',
    summary: 'Request equipment for an event from Technical Support Staff.',
    layout: 'form',
    suggestedBackendPackage: 'com.example.connect_sphere.equipmentrequest',
  },
  {
    path: 'confirm-event',
    navLabel: 'Confirm an event',
    pageTitle: 'Event Confirmation',
    storyIds: ['EC08'],
    featureArea: 'Event Status Management',
    summary: 'Confirm an event once venue and equipment arrangements are complete.',
    layout: 'detail-actions',
    suggestedBackendPackage: 'com.example.connect_sphere.event',
  },
  {
    path: 'change-review',
    navLabel: 'Review change requests',
    pageTitle: 'Event Change Impact Review',
    storyIds: ['EC-NEW3'],
    featureArea: 'Event Change Requests',
    summary: "Review an Organiser's requested change and its impact on existing venue/equipment arrangements.",
    layout: 'detail-actions',
    suggestedBackendPackage: 'com.example.connect_sphere.eventrequest (amendment, request_type = A)',
  },
]
