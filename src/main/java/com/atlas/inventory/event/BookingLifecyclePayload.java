package com.atlas.inventory.event;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record BookingLifecyclePayload(
    @NotNull
    UUID bookingId,

    UUID userId,
    String status
) {}

