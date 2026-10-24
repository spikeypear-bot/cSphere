package com.example.connect_sphere.venuebooking.hold;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;

/** EC-C3: reminds coordinators of holds entering the reminder window (venue.holds.reminder-lead). */
@Component
public class VenueHoldReminderJob {
    private final VenueHoldRepository holds;
    private final VenueHoldService service;
    public VenueHoldReminderJob(VenueHoldRepository holds,VenueHoldService service) {this.holds=holds;this.service=service;}
    @Scheduled(fixedDelayString="${venue.holds.expiry-delay-ms:30000}",initialDelayString="${venue.holds.expiry-delay-ms:30000}")
    public void remind() {
        for (var id:holds.dueForReminder()) try { service.remind(id); }
        catch (RuntimeException ex) { LoggerFactory.getLogger(getClass()).error("Hold reminder failed for {}; will retry",id,ex); }
    }
}
