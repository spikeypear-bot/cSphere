package com.example.connect_sphere.safety;

import java.util.List;

public class IncompleteReadinessException extends RuntimeException {
    private final List<String> outstandingItems;

    public IncompleteReadinessException(List<String> outstandingItems) {
        super("Safety Officer approval is blocked. Outstanding checklist items: "
                + String.join("; ", outstandingItems));
        this.outstandingItems = List.copyOf(outstandingItems);
    }

    public List<String> getOutstandingItems() {
        return outstandingItems;
    }
}
