package com.atlas.inventory.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Per-night availability of a hotel room type over {@code [from, to)} (inventory.yaml; ADR-0008).
 * Returned by {@code GET /inventory/hotel/{roomTypeId}?from&to}; backs the catalog capacity-shrink
 * validation over the affected nights.
 *
 * @param rangeMinAvailable the minimum {@code available} across the returned nights (the binding
 *                          constraint for a stay covering the whole range); {@code 0} if no nights.
 */
public record HotelAvailabilityResponse(
        UUID roomTypeId, LocalDate from, LocalDate to, List<NightAvailabilityView> nights, int rangeMinAvailable) {}
