package com.atlas.inventory.exception;

import com.atlas.inventory.entity.ReservationStatus;

/**
 * Raised when a Reservation state transition violates the Inventory state machine
 * (services/inventory/state_machine.md). Non-retryable: routed straight to the DLQ
 * by the consumer (retry-strategy.md).
 */
public class InvalidReservationStateTransitionException extends RuntimeException {

    public InvalidReservationStateTransitionException(ReservationStatus from, ReservationStatus to) {
        super("Invalid reservation state transition: " + from + " -> " + to);
    }
}
