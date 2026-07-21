package com.atlas.inventory.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Booking-facing payload: all items for a booking were reserved (inventory-events.yaml
 * InventoryReservedPayload). Keyed by {@code bookingId}. */
public record InventoryReservedPayload(
        @NotNull UUID bookingId, @NotNull BigDecimal total, @Valid @NotNull List<ReservedItem> items) {}
