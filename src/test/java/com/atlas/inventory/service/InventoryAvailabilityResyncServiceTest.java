package com.atlas.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.atlas.inventory.entity.FlightInventory;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.event.FlightAvailabilityPayload;
import com.atlas.inventory.event.HotelAvailabilityPayload;
import com.atlas.inventory.messaging.OutboxEventWriter;
import com.atlas.inventory.repository.FlightInventoryRepository;
import com.atlas.inventory.repository.RoomTypeNightAvailabilityRepository;
import com.atlas.inventory.shared.messaging.EventType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InventoryAvailabilityResyncServiceTest {

    private final FlightInventoryRepository flightRepo = mock(FlightInventoryRepository.class);
    private final RoomTypeNightAvailabilityRepository nightRepo = mock(RoomTypeNightAvailabilityRepository.class);
    private final OutboxEventWriter outbox = mock(OutboxEventWriter.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC);
    private final InventoryAvailabilityResyncService service =
            new InventoryAvailabilityResyncService(flightRepo, nightRepo, outbox, clock);

    @Test
    void resyncAll_reEmitsAbsoluteAvailabilityWithCurrentVersion() {
        UUID flightId = UUID.randomUUID();
        UUID roomTypeId = UUID.randomUUID();
        UUID hotelId = UUID.randomUUID();

        FlightInventory flight = mock(FlightInventory.class);
        when(flight.getResourceId()).thenReturn(flightId);
        when(flight.getReservedCount()).thenReturn(3);
        when(flightRepo.findAll()).thenReturn(List.of(flight));

        RoomTypeNightAvailability night = mock(RoomTypeNightAvailability.class);
        when(night.getRoomTypeId()).thenReturn(roomTypeId);
        when(night.getHotelId()).thenReturn(hotelId);
        when(night.getStayDate()).thenReturn(LocalDate.of(2026, 7, 21)); // future night (>= today)
        when(night.getReserved()).thenReturn(2);
        when(nightRepo.findAll()).thenReturn(List.of(night));

        ResyncResult result = service.resyncAll();

        ArgumentCaptor<FlightAvailabilityPayload> flightPayload =
                ArgumentCaptor.forClass(FlightAvailabilityPayload.class);
        verify(outbox)
                .write(
                        eq("Flight"),
                        eq(flightId),
                        eq(EventType.FLIGHT_SEATS_RESERVED),
                        anyString(),
                        isNull(),
                        flightPayload.capture());
        assertThat(flightPayload.getValue().resourceId()).isEqualTo(flightId);
        assertThat(flightPayload.getValue().reserved()).isEqualTo(3);
        assertThat(flightPayload.getValue().version()).isEqualTo(clock.millis());

        ArgumentCaptor<HotelAvailabilityPayload> hotelPayload = ArgumentCaptor.forClass(HotelAvailabilityPayload.class);
        verify(outbox)
                .write(
                        eq("Hotel"),
                        eq(roomTypeId),
                        eq(EventType.HOTEL_ROOMS_RESERVED),
                        anyString(),
                        isNull(),
                        hotelPayload.capture());
        assertThat(hotelPayload.getValue().roomTypeId()).isEqualTo(roomTypeId);
        assertThat(hotelPayload.getValue().nights()).hasSize(1);
        assertThat(hotelPayload.getValue().nights().getFirst().reserved()).isEqualTo(2);
        assertThat(hotelPayload.getValue().version()).isEqualTo(clock.millis());

        assertThat(result.flights()).isEqualTo(1);
        assertThat(result.roomTypes()).isEqualTo(1);
    }

    @Test
    void resyncAll_skipsPastNights() {
        when(flightRepo.findAll()).thenReturn(List.of());
        RoomTypeNightAvailability pastNight = mock(RoomTypeNightAvailability.class);
        when(pastNight.getStayDate()).thenReturn(LocalDate.of(2026, 7, 19)); // before today → skipped
        when(nightRepo.findAll()).thenReturn(List.of(pastNight));

        ResyncResult result = service.resyncAll();

        assertThat(result.flights()).isZero();
        assertThat(result.roomTypes()).isZero();
    }
}
