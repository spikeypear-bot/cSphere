import type { SkeletonFeature } from '../../types/skeletonFeature'

// EO01/EO02/EO15 are real (see EventRequestWizardPage/OrganiserHomePage) —
// these are the remaining Event Organiser stories per
// docs/product-context.md § End-to-End Workflows (step 9) that no one has
// picked up yet. Re-check against the live backlog sheet before building.
export const organiserExtraFeatures: SkeletonFeature[] = [
  {
    path: 'change-request',
    navLabel: 'Request a change',
    pageTitle: 'Event Change Request',
    storyIds: ['EO03'],
    featureArea: 'Event Change Requests',
    summary: "Request a change to an existing event's date, attendance, venue, or equipment requirements.",
    layout: 'form',
    suggestedBackendPackage: 'com.example.connect_sphere.eventrequest (amendment, request_type = A)',
  },
  {
    path: 'cancel-request',
    navLabel: 'Request cancellation',
    pageTitle: 'Event Cancellation Request',
    storyIds: ['EO07'],
    featureArea: 'Event Change Requests',
    summary: 'Request cancellation of an event.',
    layout: 'form',
    suggestedBackendPackage: 'com.example.connect_sphere.eventrequest (amendment, request_type = A)',
  },
  {
    path: 'confirmed',
    navLabel: 'View confirmed arrangements',
    pageTitle: 'Confirmed Event Details',
    storyIds: ['EO04'],
    featureArea: 'Event Information Management',
    summary: "View an event's confirmed arrangements once the Coordinator has confirmed it.",
    layout: 'detail-actions',
    suggestedBackendPackage: 'com.example.connect_sphere.event',
  },
]
