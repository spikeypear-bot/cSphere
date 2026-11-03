package com.example.connect_sphere.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A Clock a test sets and moves. The application takes its one Clock bean
 * (config/ClockConfig), so an integration test that registers this as a
 * {@code @Primary} bean controls "now" for every service:
 *
 * <pre>
 * &#64;TestConfiguration static class TimeConfig {
 *     &#64;Bean &#64;Primary MutableClock clock() { return new MutableClock(Instant.parse("2027-03-01T00:00:00Z")); }
 * }
 * </pre>
 *
 * A unit test passes one to the constructor instead. {@link #withZone} keeps
 * sharing the same time, so Singapore-date logic sees the moment the test set.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> time;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> time, ZoneId zone) {
        this.time = time;
        this.zone = zone;
    }

    public void set(Instant instant) {
        time.set(instant);
    }

    public void advance(Duration duration) {
        time.updateAndGet(now -> now.plus(duration));
    }

    @Override
    public Instant instant() {
        return time.get();
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(time, zone);
    }
}
