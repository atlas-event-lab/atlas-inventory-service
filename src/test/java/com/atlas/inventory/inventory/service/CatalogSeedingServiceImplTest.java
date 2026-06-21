package com.atlas.inventory.inventory.service;

import com.atlas.inventory.entity.Inventory;
import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.repository.ConsumedEventRepository;
import com.atlas.inventory.repository.InventoryRepository;
import com.atlas.inventory.inventory.support.InventoryTestData;
import com.atlas.inventory.service.CatalogSeedingServiceImpl;
import com.atlas.inventory.service.RoomTypeSeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static com.atlas.inventory.inventory.support.InventoryTestData.EVENT_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.FLIGHT_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.HOTEL_ID;
import static com.atlas.inventory.inventory.support.InventoryTestData.ROOM_TYPE_A;
import static com.atlas.inventory.inventory.support.InventoryTestData.ROOM_TYPE_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogSeedingServiceImplTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock ConsumedEventRepository consumedEventRepository;

    private CatalogSeedingServiceImpl newService() {
        return new CatalogSeedingServiceImpl(inventoryRepository, consumedEventRepository);
    }

    // ── Flight (AC1, AC3, AC4, AC5) ──────────────────────────────────────────

    @Test
    void upsertFlight_create_persists_ACTIVE_row_with_capacity() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_ID)).thenReturn(Optional.empty());

        newService().upsertFlight(EVENT_ID, "FlightCreated", FLIGHT_ID, 180);

        ArgumentCaptor<Inventory> saved = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepository).save(saved.capture());
        assertThat(saved.getValue().getResourceType()).isEqualTo(ResourceType.FLIGHT);
        assertThat(saved.getValue().getResourceId()).isEqualTo(FLIGHT_ID);
        assertThat(saved.getValue().getTotalCapacity()).isEqualTo(180);
        assertThat(saved.getValue().getReservedCount()).isZero();
        assertThat(saved.getValue().getStatus()).isEqualTo(InventoryStatus.ACTIVE);
        verify(consumedEventRepository).save(any());
    }

    @Test
    void upsertFlight_update_sets_absolute_capacity() {
        Inventory existing = InventoryTestData.anActiveFlight(100, 10);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_ID)).thenReturn(Optional.of(existing));

        newService().upsertFlight(EVENT_ID, "FlightUpdated", FLIGHT_ID, 250);

        assertThat(existing.getTotalCapacity()).isEqualTo(250);
        verify(inventoryRepository, never()).save(any());
        verify(consumedEventRepository).save(any());
    }

    @Test
    void upsertFlight_capacity_below_reserved_clamps_available_to_zero() {
        Inventory existing = InventoryTestData.anActiveFlight(100, 50);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_ID)).thenReturn(Optional.of(existing));

        newService().upsertFlight(EVENT_ID, "FlightUpdated", FLIGHT_ID, 30);

        assertThat(existing.getTotalCapacity()).isEqualTo(30);
        assertThat(existing.isOversold()).isTrue();
        assertThat(existing.available()).isZero();
    }

    @Test
    void upsertFlight_duplicate_event_is_skipped() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        newService().upsertFlight(EVENT_ID, "FlightCreated", FLIGHT_ID, 180);

        verify(inventoryRepository, never()).findForUpdate(any(), any());
        verify(inventoryRepository, never()).save(any());
        verify(consumedEventRepository, never()).save(any());
    }

    @Test
    void disableFlight_sets_status_DISABLED() {
        Inventory existing = InventoryTestData.anActiveFlight(100, 0);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_ID)).thenReturn(Optional.of(existing));

        newService().disableFlight(EVENT_ID, FLIGHT_ID);

        assertThat(existing.getStatus()).isEqualTo(InventoryStatus.DISABLED);
        verify(consumedEventRepository).save(any());
    }

    @Test
    void disableFlight_unknown_is_noop() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.FLIGHT, FLIGHT_ID)).thenReturn(Optional.empty());

        newService().disableFlight(EVENT_ID, FLIGHT_ID);

        verify(consumedEventRepository).save(any());
    }

    // ── Hotel (AC1, AC2, AC4) ────────────────────────────────────────────────

    @Test
    void upsertHotel_create_persists_one_row_per_room_type() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.HOTEL, ROOM_TYPE_A)).thenReturn(Optional.empty());
        when(inventoryRepository.findForUpdate(ResourceType.HOTEL, ROOM_TYPE_B)).thenReturn(Optional.empty());
        when(inventoryRepository.findByParentResourceId(HOTEL_ID)).thenReturn(List.of());

        newService().upsertHotel(EVENT_ID, "HotelCreated", HOTEL_ID,
                List.of(new RoomTypeSeed(ROOM_TYPE_A, 20), new RoomTypeSeed(ROOM_TYPE_B, 5)));

        verify(inventoryRepository, times(2)).save(any(Inventory.class));
        verify(consumedEventRepository).save(any());
    }

    @Test
    void upsertHotel_removed_room_type_is_disabled() {
        Inventory roomA = InventoryTestData.aHotelRoom(ROOM_TYPE_A, HOTEL_ID, 20, 0, InventoryStatus.ACTIVE);
        Inventory roomB = InventoryTestData.aHotelRoom(ROOM_TYPE_B, HOTEL_ID, 5, 0, InventoryStatus.ACTIVE);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findForUpdate(ResourceType.HOTEL, ROOM_TYPE_A)).thenReturn(Optional.of(roomA));
        when(inventoryRepository.findForUpdate(ResourceType.HOTEL, ROOM_TYPE_B)).thenReturn(Optional.of(roomB));
        when(inventoryRepository.findByParentResourceId(HOTEL_ID)).thenReturn(List.of(roomA, roomB));

        // HotelUpdated now carries only room type A — B was removed.
        newService().upsertHotel(EVENT_ID, "HotelUpdated", HOTEL_ID, List.of(new RoomTypeSeed(ROOM_TYPE_A, 25)));

        assertThat(roomA.getTotalCapacity()).isEqualTo(25);
        assertThat(roomA.getStatus()).isEqualTo(InventoryStatus.ACTIVE);
        assertThat(roomB.getStatus()).isEqualTo(InventoryStatus.DISABLED);
    }

    @Test
    void disableHotel_disables_every_room_type_row() {
        Inventory roomA = InventoryTestData.aHotelRoom(ROOM_TYPE_A, HOTEL_ID, 20, 0, InventoryStatus.ACTIVE);
        Inventory roomB = InventoryTestData.aHotelRoom(ROOM_TYPE_B, HOTEL_ID, 5, 0, InventoryStatus.ACTIVE);
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(false);
        when(inventoryRepository.findByParentResourceId(HOTEL_ID)).thenReturn(List.of(roomA, roomB));
        when(inventoryRepository.findForUpdate(ResourceType.HOTEL, ROOM_TYPE_A)).thenReturn(Optional.of(roomA));
        when(inventoryRepository.findForUpdate(ResourceType.HOTEL, ROOM_TYPE_B)).thenReturn(Optional.of(roomB));

        newService().disableHotel(EVENT_ID, HOTEL_ID);

        assertThat(roomA.getStatus()).isEqualTo(InventoryStatus.DISABLED);
        assertThat(roomB.getStatus()).isEqualTo(InventoryStatus.DISABLED);
        verify(consumedEventRepository).save(any());
    }

    @Test
    void upsertHotel_duplicate_event_is_skipped() {
        when(consumedEventRepository.existsById(EVENT_ID)).thenReturn(true);

        newService().upsertHotel(EVENT_ID, "HotelUpdated", HOTEL_ID, List.of(new RoomTypeSeed(ROOM_TYPE_A, 25)));

        verify(inventoryRepository, never()).findForUpdate(any(), any());
        verify(inventoryRepository, never()).findByParentResourceId(any());
        verify(consumedEventRepository, never()).save(any());
    }
}
