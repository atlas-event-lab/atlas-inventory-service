package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record BookingItemEvent(

    @NotNull
    String type,

    @NotNull
    UUID resourceId,
    Integer quantity,
    BigDecimal amount
) {}

