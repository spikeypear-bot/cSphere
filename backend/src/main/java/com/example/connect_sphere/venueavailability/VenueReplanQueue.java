package com.example.connect_sphere.venueavailability;

import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Persist before releasing the venue lock; process after commit with event-first locking. */
@Component
public class VenueReplanQueue {
    private final JdbcTemplate jdbc;
    private final VenueReplanWorker worker;
    public VenueReplanQueue(JdbcTemplate jdbc, VenueReplanWorker worker) { this.jdbc=jdbc; this.worker=worker; }

    @EventListener
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(VenueBecameUnavailable change) {
        for (UUID event:change.eventIds().stream().distinct().sorted().toList()) {
            UUID id=UUID.randomUUID();
            jdbc.update("INSERT INTO venue_replan_tasks(task_id,event_id,actor_id,reason) VALUES (?,?,?,?)",
                id,event,change.actorId(),change.reason());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { attempt(id); }
            });
        }
    }
    private void attempt(UUID id) {
        try { worker.process(id); }
        catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Venue replanning {} remains queued for retry",id,ex); }
    }
    @Scheduled(fixedDelayString="${venue.recovery.delay-ms:30000}",initialDelayString="${venue.recovery.delay-ms:30000}")
    public void retry() {
        jdbc.query("SELECT task_id FROM venue_replan_tasks WHERE completed_at IS NULL ORDER BY created_at,task_id LIMIT 100",
            (r,n) -> r.getObject(1,UUID.class)).forEach(this::attempt);
    }
}
