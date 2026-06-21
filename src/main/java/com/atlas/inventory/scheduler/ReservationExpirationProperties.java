package com.atlas.inventory.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Externalized configuration for the reservation TTL sweep
 * (coding-standards §Spring Boot/§Configuration — no hardcoded values).
 *
 * @param ttl how long a RESERVED reservation is held before the sweep expires it. Per the cascading
 *            timeout budget (state_machine.md, features/booking-expiration *Timeout Ownership*
 *            addendum) it SHALL be ≥ the Payment timeout and &lt; the Booking pending-timeout
 *            safety-net, so a reservation never expires while payment is legitimately in flight.
 *
 * <p>The sweep interval is read directly from {@code atlas.inventory.reservation.sweep-interval-ms}
 * by the scheduler's {@code @Scheduled(fixedDelayString=...)} (same approach as OutboxRelay).
 */
@ConfigurationProperties(prefix = "atlas.inventory.reservation")
public record ReservationExpirationProperties(
        Duration ttl
) {}
