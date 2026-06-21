package com.atlas.inventory.inventory.service;

import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.exception.InvalidReservationStateTransitionException;
import com.atlas.inventory.service.ReservationStateTransitionGuard;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationStateTransitionGuardTest {

    @Test
    void allows_valid_transitions() {
        assertThatCode(() -> {
            ReservationStateTransitionGuard.assertAllowed(ReservationStatus.RESERVED, ReservationStatus.CONFIRMED);
            ReservationStateTransitionGuard.assertAllowed(ReservationStatus.RESERVED, ReservationStatus.RELEASED);
            ReservationStateTransitionGuard.assertAllowed(ReservationStatus.RESERVED, ReservationStatus.EXPIRED);
            ReservationStateTransitionGuard.assertAllowed(ReservationStatus.CONFIRMED, ReservationStatus.RELEASED);
        }).doesNotThrowAnyException();
    }

    @Test
    void rejects_transitions_from_terminal_states() {
        assertThatThrownBy(() ->
                ReservationStateTransitionGuard.assertAllowed(ReservationStatus.RELEASED, ReservationStatus.CONFIRMED))
                .isInstanceOf(InvalidReservationStateTransitionException.class);
        assertThatThrownBy(() ->
                ReservationStateTransitionGuard.assertAllowed(ReservationStatus.EXPIRED, ReservationStatus.CONFIRMED))
                .isInstanceOf(InvalidReservationStateTransitionException.class);
    }

    @Test
    void rejects_confirmed_back_to_reserved_and_expiry() {
        assertThatThrownBy(() ->
                ReservationStateTransitionGuard.assertAllowed(ReservationStatus.CONFIRMED, ReservationStatus.RESERVED))
                .isInstanceOf(InvalidReservationStateTransitionException.class);
        assertThatThrownBy(() ->
                ReservationStateTransitionGuard.assertAllowed(ReservationStatus.CONFIRMED, ReservationStatus.EXPIRED))
                .isInstanceOf(InvalidReservationStateTransitionException.class);
    }
}
