package com.atlas.inventory.inventory.service;

import com.atlas.inventory.config.HotelCalendarProperties;
import com.atlas.inventory.entity.FlightInventory;
import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.repository.ConsumedEventRepository;
import com.atlas.inventory.repository.FlightInventoryRepository;
import com.atlas.inventory.repository.RoomTypeNightAvailabilityRepository;
import com.atlas.inventory.inventory.support.InventoryTestData;
import com.atlas.inventory.service.CatalogSeedingServiceImpl;
import com.atlas.inventory.service.RoomTypeSeed;
import com.atlas.inventory.shared.messaging.ConsumerEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.atlas.inventory.inventory.support.InventoryTestData.EVENT_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.FLIGHT_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.HOTEL_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.ROOM_TYPE_A;
import static com.atlas.inventory.inventory.support.InventoryTestData.ROOM_TYPE_B;
import static com.atlas.inventory.inventory.support.InventoryTestData.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogSeedingServiceImplTest {

    private static final int HORIZON_DAYS = 3; // small deterministic horizon: nights TODAY, +1, +2

    @Mock FlightInventoryRepository flightInventoryRepository;
    @Mock RoomTypeNightAvailabilityRepository roomTypeAvailabilityRepository;
    @Mock ConsumedEventRepository consumedEventRepository;

    private CatalogSeedingServiceImpl newService() {
        var properties = new HotelCalendarProperties(HORIZON_DAYS, 7);
        Clock clock = Clock.fixed(InventoryTestData.NOW, ZoneOffset.UTC);
        return new CatalogSeedingServiceImpl(flightInventoryRepository, roomTypeAvailabilityRepository,
                consumedEventRepository, properties, clock);
    }

    private List<RoomTypeNightAvailability> horizonRows(java.util.UUID roomTypeId, int totalRooms,
                                                        InventoryStatus status) {
        List<RoomTypeNightAvailability> rows = new ArrayList<>();
        for (int i = 0; i < HORIZON_DAYS; i++) {
            rows.add(new RoomTypeNightAvailability(java.util.UUID.randomUUID(), roomTypeId, HOTEL_ID,
                    TODAY.plusDays(i), totalRooms, 0, status));
        }
        return rows;
    }

    // ── Flight (scalar) ──────────────────────────────────────────────────────

    @Test
    void upsertFlight_create_persists_ACTIVE_row_with_capacity() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(flightInventoryRepository.findForUpdate(FLIGHT_ID)).thenReturn(Optional.empty());

        newService().upsertFlight(EVENT_ID, ConsumerEventType.FLIGHT_CREATED, FLIGHT_ID, 180);

        ArgumentCaptor<FlightInventory> saved = ArgumentCaptor.forClass(FlightInventory.class);
        verify(flightInventoryRepository).save(saved.capture());
        assertThat(saved.getValue().getResourceId()).isEqualTo(FLIGHT_ID);
        assertThat(saved.getValue().getTotalCapacity()).isEqualTo(180);
        assertThat(saved.getValue().getReservedCount()).isZero();
        assertThat(saved.getValue().getStatus()).isEqualTo(InventoryStatus.ACTIVE);
        verify(consumedEventRepository).save(any());
    }

    @Test
    void upsertFlight_update_sets_absolute_capacity() {
        FlightInventory existing = InventoryTestData.anActiveFlight(100, 10);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(flightInventoryRepository.findForUpdate(FLIGHT_ID)).thenReturn(Optional.of(existing));

        newService().upsertFlight(EVENT_ID, ConsumerEventType.FLIGHT_UPDATED, FLIGHT_ID, 250);

        assertThat(existing.getTotalCapacity()).isEqualTo(250);
        verify(flightInventoryRepository, never()).save(any());
        verify(consumedEventRepository).save(any());
    }

    @Test
    void disableFlight_sets_status_DISABLED() {
        FlightInventory existing = InventoryTestData.anActiveFlight(100, 0);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(flightInventoryRepository.findForUpdate(FLIGHT_ID)).thenReturn(Optional.of(existing));

        newService().disableFlight(EVENT_ID, FLIGHT_ID);

        assertThat(existing.getStatus()).isEqualTo(InventoryStatus.DISABLED);
        verify(consumedEventRepository).save(any());
    }

    @Test
    void upsertFlight_duplicate_event_is_skipped() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        newService().upsertFlight(EVENT_ID, ConsumerEventType.FLIGHT_CREATED, FLIGHT_ID, 180);

        verify(flightInventoryRepository, never()).findForUpdate(any());
        verify(flightInventoryRepository, never()).save(any());
        verify(consumedEventRepository, never()).save(any());
    }

    // ── Hotel (per-night calendar) ───────────────────────────────────────────

    @Test
    void upsertHotel_create_materializes_horizon_nights_per_room_type() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(roomTypeAvailabilityRepository.findForUpdateByHotelIdFromDate(eq(HOTEL_ID), eq(TODAY)))
                .thenReturn(List.of());

        newService().upsertHotel(EVENT_ID, ConsumerEventType.HOTEL_CREATED, HOTEL_ID,
                List.of(new RoomTypeSeed(ROOM_TYPE_A, 20), new RoomTypeSeed(ROOM_TYPE_B, 5)));

        // 2 room types × HORIZON_DAYS nights each.
        ArgumentCaptor<RoomTypeNightAvailability> saved = ArgumentCaptor.forClass(RoomTypeNightAvailability.class);
        verify(roomTypeAvailabilityRepository, times(2 * HORIZON_DAYS)).save(saved.capture());
        assertThat(saved.getAllValues())
                .allSatisfy(r -> assertThat(r.getReserved()).isZero())
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(InventoryStatus.ACTIVE))
                .anySatisfy(r -> assertThat(r.getStayDate()).isEqualTo(TODAY));
        verify(consumedEventRepository).save(any());
    }

    @Test
    void upsertHotel_update_sets_capacity_on_future_nights_and_disables_removed_room_type() {
        List<RoomTypeNightAvailability> roomA = horizonRows(ROOM_TYPE_A, 20, InventoryStatus.ACTIVE);
        List<RoomTypeNightAvailability> roomB = horizonRows(ROOM_TYPE_B, 5, InventoryStatus.ACTIVE);
        List<RoomTypeNightAvailability> all = new ArrayList<>();
        all.addAll(roomA);
        all.addAll(roomB);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(roomTypeAvailabilityRepository.findForUpdateByHotelIdFromDate(eq(HOTEL_ID), eq(TODAY)))
                .thenReturn(all);

        // HotelUpdated now carries only room type A (B was removed), new capacity 25.
        newService().upsertHotel(EVENT_ID, ConsumerEventType.HOTEL_UPDATED, HOTEL_ID,
                List.of(new RoomTypeSeed(ROOM_TYPE_A, 25)));

        assertThat(roomA).allSatisfy(n -> assertThat(n.getTotalRooms()).isEqualTo(25));
        assertThat(roomA).allSatisfy(n -> assertThat(n.getStatus()).isEqualTo(InventoryStatus.ACTIVE));
        assertThat(roomB).allSatisfy(n -> assertThat(n.getStatus()).isEqualTo(InventoryStatus.DISABLED));
        // No inserts: every horizon night already existed.
        verify(roomTypeAvailabilityRepository, never()).save(any());
    }

    @Test
    void upsertHotel_update_inserts_missing_future_nights() {
        // Existing calendar only covers the first night; the rest of the horizon must be back-filled.
        List<RoomTypeNightAvailability> partial =
                List.of(new RoomTypeNightAvailability(java.util.UUID.randomUUID(), ROOM_TYPE_A, HOTEL_ID,
                        TODAY, 20, 0, InventoryStatus.ACTIVE));
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(roomTypeAvailabilityRepository.findForUpdateByHotelIdFromDate(eq(HOTEL_ID), eq(TODAY)))
                .thenReturn(partial);

        newService().upsertHotel(EVENT_ID, ConsumerEventType.HOTEL_UPDATED, HOTEL_ID,
                List.of(new RoomTypeSeed(ROOM_TYPE_A, 20)));

        // HORIZON_DAYS nights total, 1 already existed → 2 inserted.
        verify(roomTypeAvailabilityRepository, times(HORIZON_DAYS - 1)).save(any());
    }

    @Test
    void disableHotel_disables_every_future_night() {
        List<RoomTypeNightAvailability> roomA = horizonRows(ROOM_TYPE_A, 20, InventoryStatus.ACTIVE);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(roomTypeAvailabilityRepository.findForUpdateByHotelIdFromDate(eq(HOTEL_ID), any(LocalDate.class)))
                .thenReturn(roomA);

        newService().disableHotel(EVENT_ID, HOTEL_ID);

        assertThat(roomA).allSatisfy(n -> assertThat(n.getStatus()).isEqualTo(InventoryStatus.DISABLED));
        verify(consumedEventRepository).save(any());
    }

    @Test
    void upsertHotel_duplicate_event_is_skipped() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        newService().upsertHotel(EVENT_ID, ConsumerEventType.HOTEL_UPDATED, HOTEL_ID,
                List.of(new RoomTypeSeed(ROOM_TYPE_A, 25)));

        verify(roomTypeAvailabilityRepository, never()).findForUpdateByHotelIdFromDate(any(), any());
        verify(roomTypeAvailabilityRepository, never()).save(any());
        verify(consumedEventRepository, never()).save(any());
    }
}
