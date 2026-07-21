package com.atlas.inventory.service;

import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.exception.InvalidReservationStateTransitionException;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the Reservation state machine (services/inventory/state_machine.md).
 * Terminal states (RELEASED, EXPIRED) have no outgoing transitions. CONFIRMED is non-terminal:
 * a CONFIRMED reservation may still be RELEASED on cancellation of a confirmed booking.
 */
public final class ReservationStateTransitionGuard {

    private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED = Map.of(
            ReservationStatus.RESERVED,
                    Set.of(ReservationStatus.CONFIRMED, ReservationStatus.RELEASED, ReservationStatus.EXPIRED),
            ReservationStatus.CONFIRMED, Set.of(ReservationStatus.RELEASED));

    private ReservationStateTransitionGuard() {}

    /**
     * Asserts the transition from {@code from} to {@code to} is allowed.
     *
     * @throws InvalidReservationStateTransitionException if the transition is forbidden.
     */
    public static void assertAllowed(ReservationStatus from, ReservationStatus to) {
        Set<ReservationStatus> reachable = ALLOWED.getOrDefault(from, Set.of());
        if (!reachable.contains(to)) {
            throw new InvalidReservationStateTransitionException(from, to);
        }
    }
}
