package com.atlas.inventory.service;

import com.atlas.inventory.dto.AvailabilityResponse;
import com.atlas.inventory.entity.Inventory;
import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.exception.InventoryNotFoundException;
import com.atlas.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Serves the read-only availability query (inventory.yaml). */
@Service
@RequiredArgsConstructor
public class AvailabilityQueryServiceImpl implements AvailabilityQueryService {

    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(ResourceType resourceType, UUID resourceId) {
        Inventory inventory = inventoryRepository.findByResourceTypeAndResourceId(resourceType, resourceId)
                .orElseThrow(() -> new InventoryNotFoundException(resourceType, resourceId));
        return new AvailabilityResponse(
                inventory.getResourceType(),
                inventory.getResourceId(),
                inventory.getTotalCapacity(),
                inventory.getReservedCount(),
                inventory.available(),
                inventory.getStatus());
    }
}
