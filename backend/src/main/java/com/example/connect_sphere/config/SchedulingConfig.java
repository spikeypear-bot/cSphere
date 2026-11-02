package com.example.connect_sphere.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the @Scheduled jobs: venue hold expiry and reminders, the notification outbox, the venue replan queue. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
