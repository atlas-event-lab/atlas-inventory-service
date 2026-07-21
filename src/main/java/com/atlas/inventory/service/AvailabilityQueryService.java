package com.atlas.inventory.service;

import com.atlas.inventory.dto.FlightAvailabilityResponse;
import com.atlas.inventory.dto.HotelAvailabilityResponse;
import com.atlas.inventory.exception.InventoryNotFoundException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only availability query (inventory.yaml). Backs the catalog capacity-shrink validation;
 * Inventory only serves the query — the decision lives in the catalog services (feature.md).
 */
public interface AvailabilityQueryService {

    /**
     * @return the scalar availability of a flight.
     * @throws InventoryNotFoundException if no such flight (404).
     */
    FlightAvailabilityResponse getFlightAvailability(UUID flightId);

    /**
     * @return the per-night availability of a room type over {@code [from, to)}.
     * @throws InventoryNotFoundException if the room type has no calendar rows in the range (404).
     */
    HotelAvailabilityResponse getHotelAvailability(UUID roomTypeId, LocalDate from, LocalDate to);
}
