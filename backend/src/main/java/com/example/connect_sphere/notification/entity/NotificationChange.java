package com.example.connect_sphere.notification.entity;

/** Immutable, recipient-facing comparison captured when an event change is approved. */
public record NotificationChange(String field, String label, String previousValue, String updatedValue) {
}
