import type { SkeletonFeature } from '../../types/skeletonFeature'

// Navigation to implemented Venue Staff workflows. Story metadata stays in code.
export const venueStaffFeatures: SkeletonFeature[] = [
  {
    path: 'catalogue',
    navLabel: 'Venue catalogue',
    pageTitle: 'Venue Catalogue',
    storyIds: ['VS01', 'VS06A', 'VS06B', 'VS07', 'VS18'],
    featureArea: 'Venue Catalogue',
    summary: 'Maintain the list of venues: capacity, layouts, accessibility provisions, and facilities.',
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.venue',
  },
  {
    path: 'catalogue/new',
    navLabel: 'Add a venue',
    pageTitle: 'Add Venue',
    storyIds: ['VS06A', 'VS06B'],
    featureArea: 'Venue Catalogue',
    summary: "Record a new venue's capacity, supported layouts, accessibility provisions, and facilities.",
    layout: 'form',
    suggestedBackendPackage: 'com.example.connect_sphere.venue',
  },
  {
    path: 'booking-approvals',
    navLabel: 'Booking approvals',
    pageTitle: 'Venue Booking Approval',
    storyIds: ['VS02', 'VS03', 'VS04'],
    featureArea: 'Venue Booking Approval / Booking Conflict Detection',
    summary: 'Review booking requests, manage tentative holds and reassess bookings that need attention.',
    layout: 'list',
    suggestedBackendPackage: 'com.example.connect_sphere.venuebooking',
  },
  {
    path: 'availability-calendar',
    navLabel: 'Availability calendar',
    pageTitle: 'Venue Availability Calendar',
    storyIds: ['VS15'],
    featureArea: 'Venue Availability Calendar',
    summary: 'See a calendar view of when each venue is booked or free.',
    layout: 'calendar',
    suggestedBackendPackage: 'com.example.connect_sphere.venuebooking',
  },
]
