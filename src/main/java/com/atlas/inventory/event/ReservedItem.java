package com.atlas.inventory.event;

import com.atlas.inventory.entity.ResourceType;

import java.math.BigDecimal;
import java.util.UUID;

/** A reserved booking item inside the booking-facing {@code InventoryReserved} payload
 * (inventory-events.yaml ReservedItem). */
public record ReservedItem(
        UUID reservationId,
        ResourceType resourceType,
        UUID resourceId,
        int quantity,
        BigDecimal amount
) {}
