package com.atlas.inventory.inventory.service;

import static com.atlas.inventory.inventory.support.InventoryTestData.BOOKING_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.CHECK_IN;
import static com.atlas.inventory.inventory.support.InventoryTestData.EVENT_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.FLIGHT_RESOURCE_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.HOTEL_RESOURCE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.inventory.entity.FlightInventory;
import com.atlas.inventory.entity.HotelReservation;
import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.Reservation;
import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.event.HotelAvailabilityPayload;
import com.atlas.inventory.event.InventoryRejectedPayload;
import com.atlas.inventory.exception.ReservationNotFoundException;
import com.atlas.inventory.inventory.support.InventoryTestData;
import com.atlas.inventory.messaging.OutboxEventWriter;
import com.atlas.inventory.repository.ConsumedEventRepository;
import com.atlas.inventory.repository.FlightInventoryRepository;
import com.atlas.inventory.repository.ReservationHistoryRepository;
import com.atlas.inventory.repository.ReservationRepository;
import com.atlas.inventory.repository.RoomTypeNightAvailabilityRepository;
import com.atlas.inventory.scheduler.ReservationExpirationProperties;
import com.atlas.inventory.service.InventoryServiceImpl;
import com.atlas.inventory.shared.messaging.ConsumerEventType;
import com.atlas.inventory.shared.messaging.EventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryServiceImplTest {

    @Mock
    FlightInventoryRepository flightInventoryRepository;

    @Mock
    RoomTypeNightAvailabilityRepository roomTypeAvailabilityRepository;

    @Mock
    ReservationRepository reservationRepository;

    @Mock
    ReservationHistoryRepository reservationHistoryRepository;

    @Mock
    ConsumedEventRepository consumedEventRepository;

    @Mock
    OutboxEventWriter outboxEventWriter;

    private InventoryServiceImpl newService() {
        var properties = new ReservationExpirationProperties(Duration.ofMinutes(15));
        Clock clock = Clock.fixed(InventoryTestData.NOW, ZoneOffset.UTC);
        return new InventoryServiceImpl(
                flightInventoryRepository,
                roomTypeAvailabilityRepository,
                reservationRepository,
                reservationHistoryRepository,
                consumedEventRepository,
                outboxEventWriter,
                properties,
                clock,
                new SimpleMeterRegistry());
    }

    // ── reserve flight — happy path (AC1) ────────────────────────────────────

    @Test
    void reserve_flight_happyPath_creates_RESERVED_and_publishes_absolute_reserved_event() {
        FlightInventory flight = InventoryTestData.anActiveFlight(10, 0);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(flightInventoryRepository.findForUpdate(FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(flight));

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aFlightItem(2)));

        assertThat(flight.getReservedCount()).isEqualTo(2);
        verify(reservationRepository).save(any(Reservation.class));
        // Resource-facing event is now keyed by flightId (aggregate "Flight").
        verify(outboxEventWriter)
                .write(eq("Flight"), eq(FLIGHT_RESOURCE_ID), eq(EventType.FLIGHT_SEATS_RESERVED), any(), any(), any());
        verify(outboxEventWriter)
                .write(eq("Booking"), eq(BOOKING_ID), eq(EventType.INVENTORY_RESERVED), any(), any(), any());
        verify(outboxEventWriter, never()).write(any(), any(), eq(EventType.INVENTORY_REJECTED), any(), any(), any());
        verify(consumedEventRepository).save(any());
    }

    // ── reserve hotel — happy path over the night set ─────────────────────────

    @Test
    void reserve_hotel_happyPath_reserves_every_night_and_publishes_per_night_absolute_event() {
        List<RoomTypeNightAvailability> nights = InventoryTestData.stayNights(5, 1, InventoryStatus.ACTIVE);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(roomTypeAvailabilityRepository.findForUpdateByRoomTypeIdAndStayDateIn(eq(HOTEL_RESOURCE_ID), anyList()))
                .thenReturn(nights);

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aHotelItem(2)));

        // reserved += 2 on EVERY night.
        assertThat(nights).allSatisfy(n -> assertThat(n.getReserved()).isEqualTo(3));
        verify(reservationRepository).save(any(HotelReservation.class));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter)
                .write(
                        eq("Hotel"),
                        eq(HOTEL_RESOURCE_ID),
                        eq(EventType.HOTEL_ROOMS_RESERVED),
                        any(),
                        any(),
                        payload.capture());
        HotelAvailabilityPayload hotel = (HotelAvailabilityPayload) payload.getValue();
        assertThat(hotel.nights()).hasSize(2);
        assertThat(hotel.nights()).allSatisfy(na -> assertThat(na.reserved()).isEqualTo(3)); // absolute, not delta
        assertThat(hotel.roomTypeId()).isEqualTo(HOTEL_RESOURCE_ID);
    }

    @Test
    void reserve_hotel_oneNightSoldOut_rejects_and_reserves_nothing() {
        // Night 1 has room, night 2 is sold out → the whole item is unavailable.
        RoomTypeNightAvailability night1 = InventoryTestData.aNight(CHECK_IN, 5, 0, InventoryStatus.ACTIVE);
        RoomTypeNightAvailability night2 = InventoryTestData.aNight(CHECK_IN.plusDays(1), 5, 5, InventoryStatus.ACTIVE);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(roomTypeAvailabilityRepository.findForUpdateByRoomTypeIdAndStayDateIn(eq(HOTEL_RESOURCE_ID), anyList()))
                .thenReturn(List.of(night1, night2));

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aHotelItem(1)));

        assertThat(night1.getReserved()).isZero();
        verify(reservationRepository, never()).save(any());
        verify(outboxEventWriter)
                .write(eq("Booking"), eq(BOOKING_ID), eq(EventType.INVENTORY_REJECTED), any(), any(), any());
        verify(outboxEventWriter, never()).write(any(), any(), eq(EventType.HOTEL_ROOMS_RESERVED), any(), any(), any());
    }

    @Test
    void reserve_hotel_missingNightInCalendar_is_rejected() {
        // Only one of the two stay nights exists in the calendar → cannot guarantee availability.
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(roomTypeAvailabilityRepository.findForUpdateByRoomTypeIdAndStayDateIn(eq(HOTEL_RESOURCE_ID), anyList()))
                .thenReturn(List.of(InventoryTestData.aNight(CHECK_IN, 5, 0, InventoryStatus.ACTIVE)));

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aHotelItem(1)));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter)
                .write(
                        eq("Booking"),
                        eq(BOOKING_ID),
                        eq(EventType.INVENTORY_REJECTED),
                        any(),
                        any(),
                        payload.capture());
        InventoryRejectedPayload rejected = (InventoryRejectedPayload) payload.getValue();
        assertThat(rejected.failedItems()).hasSize(1);
        assertThat(rejected.failedItems().getFirst().available()).isZero();
        verify(reservationRepository, never()).save(any());
    }

    // ── reserve — all-or-nothing across items (AC2) ──────────────────────────

    @Test
    void reserve_oneItemUnavailable_persists_nothing_and_rejects() {
        FlightInventory flight = InventoryTestData.anActiveFlight(10, 0); // available
        List<RoomTypeNightAvailability> nights = InventoryTestData.stayNights(1, 1, InventoryStatus.ACTIVE); // full
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(flightInventoryRepository.findForUpdate(FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(flight));
        when(roomTypeAvailabilityRepository.findForUpdateByRoomTypeIdAndStayDateIn(eq(HOTEL_RESOURCE_ID), anyList()))
                .thenReturn(nights);

        newService()
                .reserve(
                        EVENT_ID,
                        InventoryTestData.aReserveCommand(
                                InventoryTestData.aFlightItem(1), InventoryTestData.aHotelItem(2)));

        assertThat(flight.getReservedCount()).isZero(); // available item untouched (all-or-nothing)
        assertThat(nights).allSatisfy(n -> assertThat(n.getReserved()).isEqualTo(1));
        verify(reservationRepository, never()).save(any());
        verify(outboxEventWriter, never()).write(any(), any(), eq(EventType.INVENTORY_RESERVED), any(), any(), any());
        verify(outboxEventWriter)
                .write(eq("Booking"), eq(BOOKING_ID), eq(EventType.INVENTORY_REJECTED), any(), any(), any());
    }

    @Test
    void reserve_disabledFlight_is_rejected() {
        FlightInventory disabled = InventoryTestData.aFlight(10, 0, InventoryStatus.DISABLED);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(flightInventoryRepository.findForUpdate(FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(disabled));

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aFlightItem(1)));

        verify(reservationRepository, never()).save(any());
        verify(outboxEventWriter)
                .write(eq("Booking"), eq(BOOKING_ID), eq(EventType.INVENTORY_REJECTED), any(), any(), any());
    }

    @Test
    void reserve_unknownFlight_is_rejected_with_zero_available() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(flightInventoryRepository.findForUpdate(FLIGHT_RESOURCE_ID)).thenReturn(Optional.empty());

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aFlightItem(1)));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxEventWriter)
                .write(
                        eq("Booking"),
                        eq(BOOKING_ID),
                        eq(EventType.INVENTORY_REJECTED),
                        any(),
                        any(),
                        payload.capture());
        InventoryRejectedPayload rejected = (InventoryRejectedPayload) payload.getValue();
        assertThat(rejected.failedItems()).hasSize(1);
        assertThat(rejected.failedItems().getFirst().available()).isZero();
    }

    @Test
    void reserve_duplicateEvent_is_skipped() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        newService().reserve(EVENT_ID, InventoryTestData.aReserveCommand(InventoryTestData.aFlightItem(1)));

        verify(flightInventoryRepository, never()).findForUpdate(any());
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

    // ── release (AC4) ────────────────────────────────────────────────────────

    @Test
    void release_flight_restores_availability_and_publishes_released_event() {
        Reservation reserved = InventoryTestData.aFlightReservation(ReservationStatus.RESERVED);
        FlightInventory flight = InventoryTestData.anActiveFlight(10, 1);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(reserved));
        when(flightInventoryRepository.findForUpdate(FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(flight));

        newService()
                .release(
                        EVENT_ID,
                        BOOKING_ID,
                        ConsumerEventType.BOOKING_CANCELLED,
                        InventoryTestData.CORRELATION_ID,
                        InventoryTestData.SAGA_ID);

        assertThat(reserved.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(flight.getReservedCount()).isZero();
        verify(outboxEventWriter)
                .write(eq("Flight"), eq(FLIGHT_RESOURCE_ID), eq(EventType.FLIGHT_SEATS_RELEASED), any(), any(), any());
        verify(outboxEventWriter)
                .write(eq("Booking"), eq(BOOKING_ID), eq(EventType.INVENTORY_RELEASED), any(), any(), any());
    }

    @Test
    void release_hotel_restores_every_night_and_publishes_per_night_event() {
        HotelReservation reserved = InventoryTestData.aHotelReservation(ReservationStatus.RESERVED, 2);
        List<RoomTypeNightAvailability> nights = InventoryTestData.stayNights(5, 2, InventoryStatus.ACTIVE);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(reserved));
        when(roomTypeAvailabilityRepository.findForUpdateByRoomTypeIdAndStayDateIn(eq(HOTEL_RESOURCE_ID), anyList()))
                .thenReturn(nights);

        newService()
                .release(
                        EVENT_ID,
                        BOOKING_ID,
                        ConsumerEventType.BOOKING_CANCELLED,
                        InventoryTestData.CORRELATION_ID,
                        InventoryTestData.SAGA_ID);

        assertThat(reserved.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(nights).allSatisfy(n -> assertThat(n.getReserved()).isZero());
        verify(outboxEventWriter)
                .write(eq("Hotel"), eq(HOTEL_RESOURCE_ID), eq(EventType.HOTEL_ROOMS_RELEASED), any(), any(), any());
        verify(outboxEventWriter)
                .write(eq("Booking"), eq(BOOKING_ID), eq(EventType.INVENTORY_RELEASED), any(), any(), any());
    }

    @Test
    void release_nothingActive_emits_no_booking_event() {
        Reservation alreadyReleased = InventoryTestData.aFlightReservation(ReservationStatus.RELEASED);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(alreadyReleased));

        newService()
                .release(
                        EVENT_ID,
                        BOOKING_ID,
                        ConsumerEventType.BOOKING_FAILED,
                        InventoryTestData.CORRELATION_ID,
                        InventoryTestData.SAGA_ID);

        verify(outboxEventWriter, never()).write(any(), any(), eq(EventType.INVENTORY_RELEASED), any(), any(), any());
        verify(consumedEventRepository).save(any());
    }

    // ── expire (BookingExpired) ──────────────────────────────────────────────

    @Test
    void expire_hotel_RESERVED_transitions_to_EXPIRED_and_restores_every_night() {
        HotelReservation reserved = InventoryTestData.aHotelReservation(ReservationStatus.RESERVED, 1);
        List<RoomTypeNightAvailability> nights = InventoryTestData.stayNights(5, 1, InventoryStatus.ACTIVE);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(reservationRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(reserved));
        when(roomTypeAvailabilityRepository.findForUpdateByRoomTypeIdAndStayDateIn(eq(HOTEL_RESOURCE_ID), anyList()))
                .thenReturn(nights);

        newService().expire(EVENT_ID, BOOKING_ID, InventoryTestData.CORRELATION_ID, InventoryTestData.SAGA_ID);

        assertThat(reserved.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(nights).allSatisfy(n -> assertThat(n.getReserved()).isZero());
        verify(outboxEventWriter)
                .write(
                        eq("Hotel"),
                        eq(HOTEL_RESOURCE_ID),
                        eq(EventType.HOTEL_RESERVATION_EXPIRED),
                        any(),
                        any(),
                        any());
        verify(outboxEventWriter)
                .write(eq("Booking"), eq(BOOKING_ID), eq(EventType.INVENTORY_RELEASED), any(), any(), any());
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
    void expireReservation_flight_expires_and_emits_only_resource_event() {
        Reservation reserved = InventoryTestData.aFlightReservation(ReservationStatus.RESERVED);
        FlightInventory flight = InventoryTestData.anActiveFlight(10, 1);
        when(reservationRepository.findById(reserved.getId())).thenReturn(Optional.of(reserved));
        when(flightInventoryRepository.findForUpdate(FLIGHT_RESOURCE_ID)).thenReturn(Optional.of(flight));

        newService().expireReservation(reserved.getId());

        assertThat(reserved.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(flight.getReservedCount()).isZero();
        verify(outboxEventWriter)
                .write(
                        eq("Flight"),
                        eq(FLIGHT_RESOURCE_ID),
                        eq(EventType.FLIGHT_RESERVATION_EXPIRED),
                        any(),
                        any(),
                        any());
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
