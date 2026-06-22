package com.atlas.inventory.event;

import com.atlas.inventory.entity.ResourceType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** A booking item that could not be reserved, inside the {@code InventoryRejected} payload
 * (inventory-events.yaml FailedItem). */
public record FailedItem(
        @NotNull
        ResourceType resourceType,

        @NotNull
        UUID resourceId,
        int requested,
        int available
) {}
