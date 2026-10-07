import type { SkeletonFeature } from '../../types/skeletonFeature'

// The Lead's not-yet-built stories. IDs are spelled as in the backlog sheet.
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
