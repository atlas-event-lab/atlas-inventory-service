package com.atlas.inventory.service;

import com.atlas.inventory.exception.InventoryNotFoundException;
import com.atlas.inventory.dto.AvailabilityResponse;
import com.atlas.inventory.entity.ResourceType;

import java.util.UUID;

/**
 * Read-only availability query (inventory.yaml). Backs the catalog capacity-shrink validation;
 * Inventory only serves the query — the decision lives in the catalog services (feature.md).
 */
public interface AvailabilityQueryService {

    /**
     * @return the availability of the resource.
     * @throws InventoryNotFoundException if no such resource (404).
     */
    AvailabilityResponse getAvailability(ResourceType resourceType, UUID resourceId);
}
