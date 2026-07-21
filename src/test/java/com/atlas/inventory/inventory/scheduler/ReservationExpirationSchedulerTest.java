package com.atlas.inventory.inventory.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.inventory.entity.Reservation;
import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.inventory.support.InventoryTestData;
import com.atlas.inventory.repository.ReservationRepository;
import com.atlas.inventory.scheduler.ReservationExpirationScheduler;
import com.atlas.inventory.service.InventoryService;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationSchedulerTest {

    @Mock
    ReservationRepository reservationRepository;

    @Mock
    InventoryService inventoryService;

    private ReservationExpirationScheduler newScheduler() {
        return new ReservationExpirationScheduler(
                reservationRepository, inventoryService, Clock.fixed(InventoryTestData.NOW, ZoneOffset.UTC));
    }

    @Test
    void expireDueReservations_expires_each_due_RESERVED() {
        Reservation due = InventoryTestData.aFlightReservation(ReservationStatus.RESERVED);
        when(reservationRepository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                        eq(ReservationStatus.RESERVED), eq(InventoryTestData.NOW)))
                .thenReturn(List.of(due));

        newScheduler().expireDueReservations();

        verify(inventoryService).expireReservation(due.getId());
    }

    @Test
    void expireDueReservations_noDue_doesNothing() {
        when(reservationRepository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                        eq(ReservationStatus.RESERVED), eq(InventoryTestData.NOW)))
                .thenReturn(List.of());

        newScheduler().expireDueReservations();

        verify(inventoryService, never()).expireReservation(any());
    }

    @Test
    void expireDueReservations_failureOnOne_continuesBatch() {
        Reservation r1 = InventoryTestData.aFlightReservation(ReservationStatus.RESERVED);
        Reservation r2 = InventoryTestData.aFlightReservation(ReservationStatus.RESERVED);
        when(reservationRepository.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
                        eq(ReservationStatus.RESERVED), eq(InventoryTestData.NOW)))
                .thenReturn(List.of(r1, r2));
        doThrow(new RuntimeException("boom")).when(inventoryService).expireReservation(r1.getId());

        newScheduler().expireDueReservations();

        // Both reservations are attempted even though the first threw.
        verify(inventoryService, times(1)).expireReservation(r1.getId());
        verify(inventoryService, times(1)).expireReservation(r2.getId());
    }
}
