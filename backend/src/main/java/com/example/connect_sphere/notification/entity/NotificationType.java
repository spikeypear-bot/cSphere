package com.example.connect_sphere.notification.entity;

/** Mirrors the Postgres `notification_type` enum (V12 migration). */
public enum NotificationType {
    status_change,
    coordinator_assignment
}
