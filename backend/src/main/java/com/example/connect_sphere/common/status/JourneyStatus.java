package com.example.connect_sphere.common.status;

/**
 * EC-NEW2: the one vocabulary for where an event request or event is in its
 * journey, shown identically to every role. The stored columns
 * ({@code event_requests.status}, {@code events.status}) are inputs;
 * {@link JourneyStatusResolver} is the only place that turns them into one of
 * these, so no screen ever works a status out on its own.
 *
 * <p>Labels and explanations are written for the person reading the screen,
 * not for developers, and say who acts next (Week 1 briefing §8b: the current
 * status and outstanding actions should be easy to understand).
 */
public enum JourneyStatus {

    DRAFT(Stage.REQUEST, "Draft",
            "Not submitted yet. The organiser can keep editing it.", "Event Organiser"),
    SUBMITTED(Stage.REQUEST, "Submitted",
            "Waiting for the Event Coordinator Lead to assign a coordinator.", "Event Coordinator Lead"),
    UNDER_REVIEW(Stage.REQUEST, "Under Review",
            "An Event Coordinator is reviewing the request.", "Event Coordinator"),
    CLARIFICATION_REQUIRED(Stage.REQUEST, "Clarification Required",
            "The coordinator needs more information from the organiser before continuing.", "Event Organiser"),
    REJECTED(Stage.REQUEST, "Rejected",
            "The request was not approved.", null),
    PLANNING(Stage.EVENT, "Planning",
            "Approved. Venue and equipment arrangements are being made.", "Event Coordinator"),
    AWAITING_SAFETY_REVIEW(Stage.EVENT, "Awaiting Safety Review",
            "The Safety Officer is checking the event's operational safety.", "Safety Officer"),
    SAFETY_CHANGES_REQUESTED(Stage.EVENT, "Safety Changes Requested",
            "The Safety Officer asked for changes before the event can be confirmed.", "Event Coordinator"),
    SAFETY_APPROVED(Stage.EVENT, "Safety Approved",
            "The safety check passed. The coordinator can now confirm the event.", "Event Coordinator"),
    CONFIRMED(Stage.EVENT, "Confirmed",
            "All arrangements are confirmed.", null),
    COMPLETED(Stage.EVENT, "Completed",
            "The event has taken place.", null),
    CANCELLED(Stage.EVENT, "Cancelled",
            "This was cancelled.", null);

    public enum Stage { REQUEST, EVENT }

    private final Stage stage;
    private final String label;
    private final String explanation;
    private final String nextActor;

    JourneyStatus(Stage stage, String label, String explanation, String nextActor) {
        this.stage = stage;
        this.label = label;
        this.explanation = explanation;
        this.nextActor = nextActor;
    }

    public Stage stage() {
        return stage;
    }

    public String label() {
        return label;
    }

    public String explanation() {
        return explanation;
    }

    /** Who acts next, or null when nobody needs to. */
    public String nextActor() {
        return nextActor;
    }

    /** Rejected, Cancelled and Completed end the journey: no further actions. */
    public boolean isFinal() {
        return this == REJECTED || this == CANCELLED || this == COMPLETED;
    }
}
