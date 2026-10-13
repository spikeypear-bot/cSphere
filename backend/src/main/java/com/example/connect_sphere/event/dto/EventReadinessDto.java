package com.example.connect_sphere.event.dto;

import java.util.List;

import com.example.connect_sphere.common.status.StatusView;

/**
 * EC08: everything the coordinator needs to decide what to do next with an
 * event, worked out on the server so the page never guesses.
 *
 * @param items     one row per prerequisite (venues, equipment, safety check)
 * @param blockers  plain-language reasons the next step is not available yet
 */
public record EventReadinessDto(
        StatusView journeyStatus,
        List<Item> items,
        boolean canSendForReview,
        boolean canWithdraw,
        boolean canConfirm,
        List<String> blockers,
        String safetyFeedback,
        boolean datePassed) {

    /**
     * @param state      {@code complete}, {@code outstanding}, {@code waiting}
     *                   (someone else is acting) or {@code not_needed}
     * @param actionPath where the coordinator can resolve it, or null
     */
    public record Item(String key, String label, String state, String detail, String actionPath) {
    }
}
