package com.atlas.inventory.event;

import com.atlas.inventory.entity.ResourceType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Resource-facing payload for a single reservation item (inventory-events.yaml
 * ReservationDeltaPayload). Consumed by Search to adjust availability; keyed by {@code reservationId}.
 * Reused by the reserved / released / expired resource events, differentiated by topic. */
public record ReservationDeltaPayload(
        @NotNull
        UUID reservationId,

        @NotNull
        UUID bookingId,

        @NotNull
        ResourceType resourceType,

        @NotNull
        UUID resourceId,
        int quantity
) {}
