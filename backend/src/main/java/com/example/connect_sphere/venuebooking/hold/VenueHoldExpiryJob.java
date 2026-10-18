package com.example.connect_sphere.venuebooking.hold;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.LoggerFactory;

@Component
public class VenueHoldExpiryJob {
    private final VenueHoldRepository holds;
    private final VenueHoldService service;
    public VenueHoldExpiryJob(VenueHoldRepository holds,VenueHoldService service) {this.holds=holds;this.service=service;}
    @Scheduled(fixedDelayString="${venue.holds.expiry-delay-ms:30000}",initialDelayString="${venue.holds.expiry-delay-ms:30000}")
    public void expire() {
        for (var id:holds.overdue()) try { service.expire(id); }
        catch (RuntimeException ex) { LoggerFactory.getLogger(getClass()).error("Hold expiry failed for {}; will retry",id,ex); }
    }
}
