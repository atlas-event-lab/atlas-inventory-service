package com.atlas.inventory.dto;

import com.atlas.inventory.entity.InventoryStatus;
import java.util.UUID;

/**
 * Scalar availability of a flight (inventory.yaml). Returned by {@code GET /inventory/flight/{flightId}};
 * backs the catalog capacity-shrink validation.
 *
 * @param available {@code totalCapacity − reservedCount}, never negative.
 */
public record FlightAvailabilityResponse(
        UUID flightId, int totalCapacity, int reservedCount, int available, InventoryStatus status) {}
