package com.atlas.inventory.event;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Resource-facing hotel availability event (inventory-events.yaml HotelAvailabilityPayload, ADR-0008).
 * Consumed by Search. Keyed by {@code roomTypeId} so a single partition totally orders all updates of
 * a room type. Carries the affected {@code nights}, each with its new <b>absolute</b> {@code reserved}
 * count, plus a monotonic {@code version}; a consumer applies it only if {@code version ≥} the stored
 * version. Reused by the reserved / released / expired hotel events, differentiated by topic.
 */
public record HotelAvailabilityPayload(
        @NotNull UUID reservationId,
        @NotNull UUID bookingId,
        @NotNull UUID roomTypeId,
        @NotNull UUID hotelId,
        @NotEmpty List<NightAvailability> nights,
        long version) {}
