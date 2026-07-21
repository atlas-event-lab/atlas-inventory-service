package com.atlas.inventory.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Booking-facing payload: at least one item could not be reserved; nothing was reserved
 * (inventory-events.yaml InventoryRejectedPayload). Lists every failing item. Keyed by {@code bookingId}. */
public record InventoryRejectedPayload(@NotNull UUID bookingId, @Valid @NotNull List<FailedItem> failedItems) {}
