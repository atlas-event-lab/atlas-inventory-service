package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Resource-facing flight availability event (inventory-events.yaml FlightAvailabilityPayload, ADR-0008).
 * Consumed by Search. Keyed by {@code resourceId} (flightId) so a single partition totally orders all
 * updates of a flight. Carries the new <b>absolute</b> {@code reserved} count (not a delta) plus a
 * monotonic {@code version}; a consumer applies it only if {@code version ≥} the stored version.
 * Reused by the reserved / released / expired flight events, differentiated by topic.
 */
public record FlightAvailabilityPayload(
        @NotNull
        UUID reservationId,

        @NotNull
        UUID bookingId,

        @NotNull
        UUID resourceId,
        int reserved,
        long version
) {}
