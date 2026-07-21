package com.atlas.inventory.service;

import com.atlas.inventory.dto.FlightAvailabilityResponse;
import com.atlas.inventory.dto.HotelAvailabilityResponse;
import com.atlas.inventory.dto.NightAvailabilityView;
import com.atlas.inventory.entity.FlightInventory;
import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.exception.InventoryNotFoundException;
import com.atlas.inventory.repository.FlightInventoryRepository;
import com.atlas.inventory.repository.RoomTypeNightAvailabilityRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Serves the read-only availability query (inventory.yaml; ADR-0008). */
@Service
@RequiredArgsConstructor
public class AvailabilityQueryServiceImpl implements AvailabilityQueryService {

    private final FlightInventoryRepository flightInventoryRepository;
    private final RoomTypeNightAvailabilityRepository roomTypeAvailabilityRepository;

    @Override
    @Transactional(readOnly = true)
    public FlightAvailabilityResponse getFlightAvailability(UUID flightId) {
        FlightInventory inventory = flightInventoryRepository
                .findByResourceId(flightId)
                .orElseThrow(() -> new InventoryNotFoundException(ResourceType.FLIGHT, flightId));
        return new FlightAvailabilityResponse(
                inventory.getResourceId(),
                inventory.getTotalCapacity(),
                inventory.getReservedCount(),
                inventory.available(),
                inventory.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public HotelAvailabilityResponse getHotelAvailability(UUID roomTypeId, LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) {
            throw new IllegalArgumentException("'to' must be after 'from': from=" + from + ", to=" + to);
        }
        List<RoomTypeNightAvailability> rows =
                roomTypeAvailabilityRepository.findByRoomTypeIdInRange(roomTypeId, from, to);
        if (rows.isEmpty()) {
            throw new InventoryNotFoundException(ResourceType.HOTEL, roomTypeId);
        }
        List<NightAvailabilityView> nights = rows.stream()
                .map(r -> new NightAvailabilityView(
                        r.getStayDate(), r.getTotalRooms(), r.getReserved(), r.available(), r.getStatus()))
                .toList();
        int rangeMinAvailable =
                nights.stream().mapToInt(NightAvailabilityView::available).min().orElse(0);
        return new HotelAvailabilityResponse(roomTypeId, from, to, nights, rangeMinAvailable);
    }
}
