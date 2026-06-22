package com.atlas.inventory.event;

import com.atlas.inventory.entity.ResourceType;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** A reserved booking item inside the booking-facing {@code InventoryReserved} payload
 * (inventory-events.yaml ReservedItem). */
public record ReservedItem(
        @NotNull
        UUID reservationId,

        @NotNull
        ResourceType resourceType,

        @NotNull
        UUID resourceId,
        int quantity,
        BigDecimal amount
) {}
