import type { SkeletonFeature } from '../../types/skeletonFeature'

// The Event Coordinator Lead's not-yet-built stories (Week 7 customer change
// 5). Story IDs are copied exactly as the live backlog sheet spells them —
// including `ELC-` on C4–C6, which the sheet has alongside `ECL-` on C1–C3 —
// so a card can be traced to its row; re-check the sheet before building one.
// 'unassigned-requests' (ECL-C1) is not in this list: it is a real page
// (UnassignedRequestsPage, routed directly in App.tsx), reached from the
// console's own primary button, the same way coordinatorFeatures leaves out
// 'review-queue'.
export const coordinatorLeadFeatures: SkeletonFeature[] = [
  {
    path: 'assignments',
    navLabel: 'Coordinator assignments',
    pageTitle: 'Coordinator Assignments',
    storyIds: ['ECL-C2', 'ELC-C6', 'ELC-C5'],
    featureArea: 'Coordinator Assignment',
    summary:
      'See every assigned event request and the Event Coordinator handling it, assign an unassigned request, and reassign one when availability changes.',
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.eventrequest',
  },
  {
    path: 'request-review',
    navLabel: 'Review incoming requests',
    pageTitle: 'Incoming Request Review',
    storyIds: ['ECL-C3'],
    featureArea: 'Event Review and Approval',
    summary: 'Open an incoming event request to review its details before deciding who should coordinate it.',
    layout: 'detail-actions',
    suggestedBackendPackage: 'com.example.connect_sphere.eventrequest',
  },
  {
    path: 'active-events',
    navLabel: 'Active events',
    pageTitle: 'Active Events',
    storyIds: ['ELC-C4'],
    featureArea: 'Coordinator Assignment',
    summary: 'See all active events and who is coordinating each, to judge workload before assigning a new request.',
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.event',
  },
]
