package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Booking-facing payload: a booking's reservations were released (compensation)
 * (inventory-events.yaml InventoryReleasedPayload). Keyed by {@code bookingId}. */
public record InventoryReleasedPayload(
        @NotNull
        UUID bookingId,

        @NotNull
        List<UUID> reservationIds
) {}
