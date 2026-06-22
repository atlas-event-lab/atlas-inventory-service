package com.atlas.inventory.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Booking-facing payload: all items for a booking were reserved (inventory-events.yaml
 * InventoryReservedPayload). Keyed by {@code bookingId}. */
public record InventoryReservedPayload(
        UUID bookingId,
        BigDecimal total,
        List<ReservedItem> items
) {}
