package com.example.connect_sphere.changerequest;

import java.util.List;
import java.util.UUID;

/** EO03/EC-NEW3 refusals, each mapped to its HTTP status in ChangeRequestController. */
public final class ChangeRequestException {

    private ChangeRequestException() {
    }

    /** 404: unknown, or belongs to another organisation (never confirm it exists). */
    public static class NotFound extends RuntimeException {
        public NotFound(UUID id) {
            super("Change request not found: " + id);
        }
    }

    /** 409: the request or event is not in a state where this can happen now. */
    public static class State extends RuntimeException {
        public State(String message) {
            super(message);
        }
    }

    /** 409: the arrangements changed after the coordinator reviewed the impact. */
    public static class ImpactChanged extends State {
        private final transient List<ChangeRequestDto.ImpactItem> impact;

        public ImpactChanged(List<ChangeRequestDto.ImpactItem> impact) {
            super("The impact of this change has changed since you reviewed it. Review the updated impact and approve again.");
            this.impact = List.copyOf(impact);
        }

        public List<ChangeRequestDto.ImpactItem> getImpact() {
            return impact;
        }
    }
}
