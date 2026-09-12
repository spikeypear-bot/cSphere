import type { SkeletonFeature } from '../../types/skeletonFeature'

// Story IDs per docs/product-context.md § End-to-End Workflows (step 6),
// which names TS01/TS02/TS03 directly — re-check against the live backlog
// sheet before building any of these for real.
export const technicalSupportFeatures: SkeletonFeature[] = [
  {
    path: 'availability',
    navLabel: 'Check availability',
    pageTitle: 'Equipment Availability',
    storyIds: ['TS01'],
    featureArea: 'Equipment Availability Checking',
    summary: 'Check how much of an equipment type is available for a requested date range.',
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.equipment',
  },
  {
    path: 'reservations',
    navLabel: 'Reservations',
    pageTitle: 'Equipment Reservations',
    storyIds: ['TS02'],
    featureArea: 'Equipment Reservation',
    summary: 'Reserve equipment for an event and see existing equipment reservations.',
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.equipmentrequest',
  },
  {
    path: 'status',
    navLabel: 'Equipment status',
    pageTitle: 'Equipment Status',
    storyIds: ['TS03'],
    featureArea: 'Equipment Request Management',
    summary: "Update an equipment item's operational status (available / faulty / unavailable) without altering existing reservations.",
    layout: 'detail-actions',
    suggestedBackendPackage: 'com.example.connect_sphere.equipment',
  },
]
