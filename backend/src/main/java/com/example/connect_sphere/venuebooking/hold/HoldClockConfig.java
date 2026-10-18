package com.example.connect_sphere.venuebooking.hold;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class HoldClockConfig {
    @Bean public Clock venueHoldClock() { return Clock.systemUTC(); }
}
