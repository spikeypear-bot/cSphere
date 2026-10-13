package com.example.connect_sphere.common.status;

/**
 * EC-NEW2: a status as every API response carries it, so all screens show the
 * same label and explanation without keeping their own copies.
 *
 * @param code        the {@link JourneyStatus} name, for styling and filters
 * @param datePassed  the event's end has passed but it was never confirmed;
 *                    the coordinator must resolve it
 */
public record StatusView(
        String code,
        String label,
        String explanation,
        String nextActor,
        String stage,
        boolean isFinal,
        boolean datePassed) {

    public static StatusView of(JourneyStatus status, boolean datePassed) {
        return new StatusView(status.name(), status.label(), status.explanation(), status.nextActor(),
                status.stage().name(), status.isFinal(), datePassed);
    }
}
