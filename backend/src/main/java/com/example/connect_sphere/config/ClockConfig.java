package com.example.connect_sphere.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's one source of "now". Every service takes this Clock and
 * calls {@code now(clock)} instead of {@code now()}, so a test can fix or
 * move time for the whole application by overriding this one bean.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
