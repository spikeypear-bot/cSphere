package com.example.connect_sphere.venueavailability;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.connect_sphere.event.service.EventLifecycleService;

@Service
public class VenueReplanWorker {
    private final JdbcTemplate jdbc;
    private final EventLifecycleService lifecycle;
    public VenueReplanWorker(JdbcTemplate jdbc, EventLifecycleService lifecycle) { this.jdbc=jdbc; this.lifecycle=lifecycle; }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void process(UUID id) {
        var tasks=jdbc.query("SELECT event_id,actor_id,reason FROM venue_replan_tasks WHERE task_id=? AND completed_at IS NULL FOR UPDATE SKIP LOCKED",
            (r,n) -> new Task(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),id);
        if (tasks.isEmpty()) return;
        var task=tasks.getFirst();
        lifecycle.returnToPlanningForVenueImpact(task.event(),task.actor(),task.reason());
        jdbc.update("UPDATE venue_replan_tasks SET completed_at=clock_timestamp() WHERE task_id=?",id);
    }
    private record Task(UUID event,UUID actor,String reason) {}
}
