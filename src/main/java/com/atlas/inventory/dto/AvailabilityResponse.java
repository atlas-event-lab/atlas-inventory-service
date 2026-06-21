package com.atlas.inventory.dto;

import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.ResourceType;

import java.util.UUID;

/**
 * Availability of a resource (inventory.yaml {@code AvailabilityResponse}). Returned by
 * {@code GET /inventory/{resourceType}/{resourceId}}. The entity enums serialize 1:1 to the
 * contract's FLIGHT/HOTEL and ACTIVE/DISABLED.
 *
 * @param available {@code totalCapacity − reservedCount}, never negative.
 */
public record AvailabilityResponse(
        ResourceType resourceType,
        UUID resourceId,
        int totalCapacity,
        int reservedCount,
        int available,
        InventoryStatus status
) {}
