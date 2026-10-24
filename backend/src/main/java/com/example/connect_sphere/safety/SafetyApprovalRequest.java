package com.example.connect_sphere.safety;

import java.util.List;

/**
 * @param flaggedItems SO-C5/SO-C6: the checklist item keys a rejection or a
 *                     request for changes concerns; none for an approval
 */
public record SafetyApprovalRequest(String decision, String note, List<String> flaggedItems) {

    public SafetyApprovalRequest(String decision, String note) {
        this(decision, note, null);
    }
}
