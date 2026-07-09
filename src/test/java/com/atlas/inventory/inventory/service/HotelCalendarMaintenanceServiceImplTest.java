package com.atlas.inventory.inventory.service;

import com.atlas.inventory.config.HotelCalendarProperties;
import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.repository.RoomTypeNightAvailabilityRepository;
import com.atlas.inventory.inventory.support.InventoryTestData;
import com.atlas.inventory.service.HotelCalendarMaintenanceServiceImpl;
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
import java.util.List;
import java.util.UUID;

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
class HotelCalendarMaintenanceServiceImplTest {

    private static final int HORIZON_DAYS = 3;

    @Mock RoomTypeNightAvailabilityRepository repository;

    private HotelCalendarMaintenanceServiceImpl newService() {
        var properties = new HotelCalendarProperties(HORIZON_DAYS, 7);
        Clock clock = Clock.fixed(InventoryTestData.NOW, ZoneOffset.UTC);
        return new HotelCalendarMaintenanceServiceImpl(repository, properties, clock);
    }

    private RoomTypeNightAvailability night(UUID roomTypeId, LocalDate date) {
        return new RoomTypeNightAvailability(UUID.randomUUID(), roomTypeId, HOTEL_ID, date, 10, 0,
                InventoryStatus.ACTIVE);
    }

    @Test
    void rollHorizonForward_clones_missing_frontier_nights() {
        LocalDate frontier = TODAY.plusDays(HORIZON_DAYS - 1L);
        LocalDate source = frontier.minusDays(1);
        // Two room types at the source night; only A already extended to the frontier.
        when(repository.findByStayDate(source)).thenReturn(List.of(night(ROOM_TYPE_A, source), night(ROOM_TYPE_B, source)));
        when(repository.findByStayDate(frontier)).thenReturn(List.of(night(ROOM_TYPE_A, frontier)));

        int created = newService().rollHorizonForward();

        assertThat(created).isEqualTo(1); // only B is missing the frontier night
        ArgumentCaptor<RoomTypeNightAvailability> saved = ArgumentCaptor.forClass(RoomTypeNightAvailability.class);
        verify(repository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getRoomTypeId()).isEqualTo(ROOM_TYPE_B);
        assertThat(saved.getValue().getStayDate()).isEqualTo(frontier);
        assertThat(saved.getValue().getReserved()).isZero();
    }

    @Test
    void purgePastNights_deletes_before_retention_cutoff() {
        when(repository.deleteByStayDateBefore(any())).thenReturn(4);

        int deleted = newService().purgePastNights();

        assertThat(deleted).isEqualTo(4);
        verify(repository).deleteByStayDateBefore(eq(TODAY.minusDays(7)));
    }

    @Test
    void rollHorizonForward_allExtended_createsNothing() {
        LocalDate frontier = TODAY.plusDays(HORIZON_DAYS - 1L);
        LocalDate source = frontier.minusDays(1);
        when(repository.findByStayDate(source)).thenReturn(List.of(night(ROOM_TYPE_A, source)));
        when(repository.findByStayDate(frontier)).thenReturn(List.of(night(ROOM_TYPE_A, frontier)));

        int created = newService().rollHorizonForward();

        assertThat(created).isZero();
        verify(repository, never()).save(any());
    }
}
