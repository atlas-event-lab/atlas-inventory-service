package com.atlas.inventory.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record BookingCreatedPayload(
        @NotNull UUID bookingId,
        @NotNull UUID userId,
        @Valid @NotNull List<BookingItemEvent> items,
        Integer travelers,
        @NotNull MoneyEvent total) {}
