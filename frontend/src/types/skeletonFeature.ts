/**
 * Describes one not-yet-built page so it can render as a real, navigable
 * placeholder instead of a "coming soon" toast. This exists so every role
 * selectable on the main page lands on an actual page from day one — giving
 * whoever picks up that story next sprint a concrete file and route to build
 * on, per Jillian's request to give the team "a basis to work on."
 *
 * `storyIds` are this session's best-known mapping from
 * docs/product-context.md's End-to-End Workflows (section 4) and Detailed
 * User Stories (section 5) — re-check exact IDs, acceptance criteria, and
 * current `Assigned To` on the live backlog sheet before starting real work
 * on any of these pages, since scope/ownership can move (see AI_Context.md
 * "Product Backlog Spreadsheet" rules).
 */
export type SkeletonLayout = 'list' | 'form' | 'calendar' | 'detail-actions'

export interface SkeletonFeature {
  /** URL segment relative to the role's base path, e.g. "review-queue". */
  path: string
  /** Short label for the nav/home card. */
  navLabel: string
  /** Full page heading. */
  pageTitle: string
  /** Backlog story IDs this page exists to satisfy. */
  storyIds: string[]
  /** Which named Release 1 core feature (AI_Context.md) this maps to. */
  featureArea: string
  /** One sentence: what this page will actually do once someone builds it. */
  summary: string
  /** Rough shape of the eventual UI, so the placeholder previews its future self. */
  layout: SkeletonLayout
  /**
   * The backend package this story's vertical slice should live in, following
   * the `eventrequest` package's Controller → Service → Repository → Entity →
   * DTO/Mapper layering (see AI_Context.md "Development Conventions"). Named here
   * — not created yet — so the "strand of hair" shape (backend through
   * frontend) is visible from this page, not just the UI half of it.
   */
  suggestedBackendPackage: string
}
