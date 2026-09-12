import type { SkeletonFeature } from '../../types/skeletonFeature'

// Story IDs per docs/product-context.md § End-to-End Workflows (step 8),
// which names AT01–AT04/AT07 — re-check against the live backlog sheet
// before building any of these for real (AT05/AT08 are also known
// duplicate-ID rows — see docs/decision-log.md Q6).
export const attendeeFeatures: SkeletonFeature[] = [
  {
    path: 'browse',
    navLabel: 'Browse events',
    pageTitle: 'Browse Events',
    storyIds: ['AT01'],
    featureArea: 'Attendee Registration',
    summary: 'See events open for registration and their key details.',
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.event',
  },
  {
    path: 'register',
    navLabel: 'Register for an event',
    pageTitle: 'Event Registration',
    storyIds: ['AT02', 'AT04'],
    featureArea: 'Attendee Registration',
    summary: "Register for an event within its capacity and registration period.",
    layout: 'form',
    suggestedBackendPackage: 'com.example.connect_sphere.attendeeregistration',
  },
  {
    path: 'my-registrations',
    navLabel: 'My registrations',
    pageTitle: 'My Registrations',
    storyIds: ['AT03', 'AT07'],
    featureArea: 'Attendee Registration',
    summary: "View your registration status and confirmation for events you've registered for.",
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.attendeeregistration',
  },
]
