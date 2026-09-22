package com.example.connect_sphere.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @Column(name = "event_id")
    private UUID id;

    @Column(name = "event_name", nullable = false)
    private String name;

    @Column(name = "start_datetime", nullable = false)
    private OffsetDateTime startDatetime;

    @Column(name = "end_datetime", nullable = false)
    private OffsetDateTime endDatetime;

    protected Event() {}

    public UUID getId() { return id; }
    public String getName() { return name; }
    public OffsetDateTime getStartDatetime() { return startDatetime; }
    public OffsetDateTime getEndDatetime() { return endDatetime; }
}