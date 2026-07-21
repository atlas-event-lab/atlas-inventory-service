package com.atlas.inventory.exception;

import com.atlas.inventory.entity.ResourceType;
import java.util.UUID;

/**
 * Raised when an {@code Inventory} row referenced by a reservation cannot be found.
 * Non-retryable: a re-delivery would fail identically, so the consumer routes it to the DLQ
 * (retry-strategy.md). During reservation an unknown resource is NOT raised as this exception —
 * it is treated as unavailable and reported in {@code InventoryRejected} (feature.md).
 */
public class InventoryNotFoundException extends RuntimeException {

    public InventoryNotFoundException(ResourceType resourceType, UUID resourceId) {
        super("Inventory not found: resourceType=" + resourceType + ", resourceId=" + resourceId);
    }
}
