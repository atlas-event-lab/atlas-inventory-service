package com.atlas.inventory.inventory.service;

import com.atlas.inventory.entity.Inventory;
import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.Reservation;
import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.event.InventoryRejectedPayload;
import com.atlas.inventory.exception.ReservationNotFoundException;
import com.atlas.inventory.messaging.OutboxEventWriter;
import com.atlas.inventory.repository.ConsumedEventRepository;
import com.atlas.inventory.repository.InventoryRepository;
import com.atlas.inventory.repository.ReservationHistoryRepository;
import com.atlas.inventory.repository.ReservationRepository;
import com.atlas.inventory.scheduler.ReservationExpirationProperties;
import com.atlas.inventory.inventory.support.InventoryTestData;
import com.atlas.inventory.service.InventoryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.atlas.inventory.inventory.support.InventoryTestData.BOOKING_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.EVENT_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.FLIGHT_RESOURCE_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.HOTEL_RESOURCE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceImplTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock ReservationHistoryRepository reservationHistoryRepository;
    @Mock ConsumedEventRepository consumedEventRepository;
    @Mock OutboxEventWriter outboxEventWriter;

    private InventoryServiceImpl newService() {
        var properties = new ReservationExpirationProperties(Duration.ofMinutes(15));
        Clock clock = Clock.fixed(InventoryTestData.NOW, ZoneOffset.UTC);
        return new InventoryServiceImpl(inventoryRepository, reservationRepository,
                reservationHistoryRepository, consumedEventRepository, outboxEventWriter, properties, clock);
    }

    // ── reserve — happy path (AC1) ───────────────────────────────────────────

    @Test
    void reserve_happyPath_creates_RESERVED_and_publishes_reserved_events() {
        Inventory flight = InventoryTestData.anActiveFlight(10, 0);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID))
                .thenReturn(Optional.of(flight));

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aFlightItem(2)));

        assertThat(flight.getReservedCount()).isEqualTo(2);
        verify(reservationRepository).save(any(Reservation.class));
        verify(outboxEventWriter).write(eq("Reservation"), any(), eq("FlightSeatsReserved"), any(), any(), any());
        verify(outboxEventWriter).write(eq("Booking"), eq(BOOKING_ID), eq("InventoryReserved"), any(), any(), any());
        verify(outboxEventWriter, never()).write(any(), any(), eq("InventoryRejected"), any(), any(), any());
        verify(consumedEventRepository).save(any());
    }

    // ── reserve — all-or-nothing rejection (AC2) ─────────────────────────────

    @Test
    void reserve_oneItemUnavailable_persists_nothing_and_rejects_with_all_failed_items() {
        Inventory flight = InventoryTestData.anActiveFlight(10, 0);   // available
        Inventory hotel  = InventoryTestData.anActiveHotel(1, 1);     // full → unavailable
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(flight));
        when(inventoryRepository.findForUpdate(ResourceType.HOTEL, HOTEL_RESOURCE_ID)).thenReturn(Optional.of(hotel));

        newService().reserve(EVENT_ID,
                InventoryTestData.aReserveCommand(InventoryTestData.aFlightItem(1), InventoryTestData.aHotelItem(2)));

        // Nothing reserved on the available item either (all-or-nothing).
        assertThat(flight.getReservedCount()).isZero();
        verify(reservationRepository, never()).save(any());
        verify(outboxEventWriter, never()).write(any(), any(), eq("InventoryReserved"), any(), any(), any());

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter).write(eq("Booking"), eq(BOOKING_ID), eq("InventoryRejected"),
                any(), any(), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(InventoryRejectedPayload.class);
        InventoryRejectedPayload rejected = (InventoryRejectedPayload) payload.getValue();
        assertThat(rejected.failedItems()).hasSize(1);
        assertThat(rejected.failedItems().getFirst().resourceType()).isEqualTo(ResourceType.HOTEL);
        assertThat(rejected.failedItems().getFirst().available()).isZero();
    }

    @Test
    void reserve_disabledResource_is_rejected() {
        Inventory disabled = InventoryTestData.anInventory(
                ResourceType.FLIGHT, FLIGHT_RESOURCE_ID, 10, 0, InventoryStatus.DISABLED);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(disabled));

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aFlightItem(1)));

        verify(reservationRepository, never()).save(any());
        verify(outboxEventWriter).write(eq("Booking"), eq(BOOKING_ID), eq("InventoryRejected"), any(), any(), any());
    }

    @Test
    void reserve_unknownResource_is_rejected_with_zero_available() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID)).thenReturn(Optional.empty());

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aFlightItem(1)));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter).write(eq("Booking"), eq(BOOKING_ID), eq("InventoryRejected"),
                any(), any(), payload.capture());
        InventoryRejectedPayload rejected = (InventoryRejectedPayload) payload.getValue();
        assertThat(rejected.failedItems()).hasSize(1);
        assertThat(rejected.failedItems().getFirst().available()).isZero();
    }

    @Test
    void reserve_duplicateEvent_is_skipped() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aFlightItem(1)));

        verify(inventoryRepository, never()).findForUpdate(any(), any());
        verify(reservationRepository, never()).save(any());
        verify(consumedEventRepository, never()).save(any());
        verify(outboxEventWriter, never()).write(any(), any(), any(), any(), any(), any());
    }

    // ── confirm (AC3) ────────────────────────────────────────────────────────

    @Test
    void confirm_transitions_RESERVED_to_CONFIRMED_without_event() {
        Reservation reserved = InventoryTestData.aFlightReservation(ReservationStatus.RESERVED);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(reserved));

        newService().confirm(EVENT_ID, BOOKING_ID);

        assertThat(reserved.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(consumedEventRepository).save(any());
        verify(outboxEventWriter, never()).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    void confirm_guardMismatch_already_released_is_noop() {
        Reservation released = InventoryTestData.aFlightReservation(ReservationStatus.RELEASED);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(released));

        newService().confirm(EVENT_ID, BOOKING_ID);

        assertThat(released.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(consumedEventRepository).save(any());
    }

    // ── release (AC4) ────────────────────────────────────────────────────────

    @Test
    void release_RESERVED_restores_availability_and_publishes_released_events() {
        Reservation reserved = InventoryTestData.aFlightReservation(ReservationStatus.RESERVED);
        Inventory flight = InventoryTestData.anActiveFlight(10, 1);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(reserved));
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(flight));

        newService().release(EVENT_ID, BOOKING_ID, "BookingCancelled",
                InventoryTestData.CORRELATION_ID, InventoryTestData.SAGA_ID);

        assertThat(reserved.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(flight.getReservedCount()).isZero();
        verify(outboxEventWriter).write(eq("Reservation"), any(), eq("FlightSeatsReleased"), any(), any(), any());
        verify(outboxEventWriter).write(eq("Booking"), eq(BOOKING_ID), eq("InventoryReleased"), any(), any(), any());
    }

    @Test
    void release_CONFIRMED_transitions_to_RELEASED() {
        Reservation confirmed = InventoryTestData.aFlightReservation(ReservationStatus.CONFIRMED);
        Inventory flight = InventoryTestData.anActiveFlight(10, 1);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(confirmed));
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(flight));

        newService().release(EVENT_ID, BOOKING_ID, "BookingCancelled",
                InventoryTestData.CORRELATION_ID, InventoryTestData.SAGA_ID);

        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(flight.getReservedCount()).isZero();
    }

    @Test
    void release_nothingActive_emits_no_booking_event() {
        Reservation alreadyReleased = InventoryTestData.aFlightReservation(ReservationStatus.RELEASED);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(alreadyReleased));

        newService().release(EVENT_ID, BOOKING_ID, "BookingFailed",
                InventoryTestData.CORRELATION_ID, InventoryTestData.SAGA_ID);

        verify(outboxEventWriter, never()).write(any(), any(), eq("InventoryReleased"), any(), any(), any());
        verify(consumedEventRepository).save(any());
    }

    // ── expire (BookingExpired) ──────────────────────────────────────────────

    @Test
    void expire_RESERVED_transitions_to_EXPIRED_and_restores_availability() {
        Reservation reserved = InventoryTestData.aFlightReservation(ReservationStatus.RESERVED);
        Inventory flight = InventoryTestData.anActiveFlight(10, 1);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(reserved));
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(flight));

        newService().expire(EVENT_ID, BOOKING_ID, InventoryTestData.CORRELATION_ID, InventoryTestData.SAGA_ID);

        assertThat(reserved.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(flight.getReservedCount()).isZero();
        verify(outboxEventWriter).write(eq("Reservation"), any(), eq("FlightReservationExpired"), any(), any(), any());
        verify(outboxEventWriter).write(eq("Booking"), eq(BOOKING_ID), eq("InventoryReleased"), any(), any(), any());
    }

    @Test
    void expire_nothingReserved_is_noop() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of());

        newService().expire(EVENT_ID, BOOKING_ID, InventoryTestData.CORRELATION_ID, InventoryTestData.SAGA_ID);

        verify(outboxEventWriter, never()).write(any(), any(), any(), any(), any(), any());
        verify(consumedEventRepository).save(any());
    }

    // ── TTL sweep (AC5) ──────────────────────────────────────────────────────

    @Test
    void expireReservation_RESERVED_expires_and_emits_only_resource_event() {
        Reservation reserved = InventoryTestData.aFlightReservation(ReservationStatus.RESERVED);
        Inventory flight = InventoryTestData.anActiveFlight(10, 1);
        when(reservationRepository.findById(reserved.getId())).thenReturn(Optional.of(reserved));
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(flight));

        newService().expireReservation(reserved.getId());

        assertThat(reserved.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(flight.getReservedCount()).isZero();
        verify(outboxEventWriter).write(eq("Reservation"), any(), eq("FlightReservationExpired"), any(), any(), any());
        // The TTL sweep never emits a booking-facing event.
        verify(outboxEventWriter, never()).write(eq("Booking"), any(), any(), any(), any(), any());
    }

    @Test
    void expireReservation_notReserved_is_noop() {
        Reservation confirmed = InventoryTestData.aFlightReservation(ReservationStatus.CONFIRMED);
        when(reservationRepository.findById(confirmed.getId())).thenReturn(Optional.of(confirmed));

        newService().expireReservation(confirmed.getId());

        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        verify(outboxEventWriter, never()).write(any(), any(), any(), any(), any(), any());
    }

    @Test
    void expireReservation_notFound_throws() {
        when(reservationRepository.findById(InventoryTestData.RESERVATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().expireReservation(InventoryTestData.RESERVATION_ID))
                .isInstanceOf(ReservationNotFoundException.class);
    }
}
