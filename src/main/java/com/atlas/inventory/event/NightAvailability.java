package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * The new <b>absolute</b> reserved count for one night of a room type
 * (inventory-events.yaml NightAvailability, ADR-0008).
 */
public record NightAvailability(
        @NotNull
        LocalDate stayDate,
        
        int reserved
) {}
