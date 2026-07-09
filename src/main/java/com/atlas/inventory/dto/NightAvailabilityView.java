package com.atlas.inventory.dto;

import com.atlas.inventory.entity.InventoryStatus;

import java.time.LocalDate;

/** One night of a room type's availability (inventory.yaml), inside {@link HotelAvailabilityResponse}. */
public record NightAvailabilityView(
        LocalDate stayDate,
        int totalRooms,
        int reserved,
        int available,
        InventoryStatus status
) {}
