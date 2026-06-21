package com.atlas.inventory.event;

import java.util.List;
import java.util.UUID;

/** Booking-facing payload: a booking's reservations were released (compensation)
 * (inventory-events.yaml InventoryReleasedPayload). Keyed by {@code bookingId}. */
public record InventoryReleasedPayload(
        UUID bookingId,
        List<UUID> reservationIds
) {}
