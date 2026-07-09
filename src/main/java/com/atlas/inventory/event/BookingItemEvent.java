package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One item of a consumed {@code BookingCreated} (booking-events.yaml BookingItem, ADR-0010).
 * {@code checkIn}/{@code checkOut} are present for HOTEL items (the stay range Inventory reserves per
 * night, {@code [checkIn, checkOut)}) and omitted for FLIGHT items.
 */
public record BookingItemEvent(

    @NotNull
    String type,

    @NotNull
    UUID resourceId,
    Integer quantity,
    BigDecimal amount,
    LocalDate checkIn,
    LocalDate checkOut
) {}
