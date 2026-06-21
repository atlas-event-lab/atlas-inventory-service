package com.atlas.inventory.exception;

import java.util.UUID;

/**
 * Raised when a {@code Reservation} referenced by id cannot be found (e.g. during the TTL sweep).
 * Non-retryable: routed straight to the DLQ by the consumer (retry-strategy.md).
 */
public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID reservationId) {
        super("Reservation not found: reservationId=" + reservationId);
    }
}
