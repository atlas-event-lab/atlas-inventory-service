package com.atlas.inventory.service;

import com.atlas.inventory.config.HotelCalendarProperties;
import com.atlas.inventory.entity.ConsumedEvent;
import com.atlas.inventory.entity.FlightInventory;
import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.repository.ConsumedEventRepository;
import com.atlas.inventory.repository.FlightInventoryRepository;
import com.atlas.inventory.repository.RoomTypeNightAvailabilityRepository;
import com.atlas.inventory.shared.messaging.ConsumerEventType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains availability from Flight/Hotel catalog events (features/seed-inventory-from-catalog;
 * ADR-0008). Idempotent on the envelope {@code eventId}; publishes nothing. Flights use the scalar
 * {@link FlightInventory}; hotels materialize a per-night calendar in
 * {@link RoomTypeNightAvailability} over the booking horizon {@code [today, today + horizonDays)}.
 * Writes take the pessimistic lock so seeding serializes with the reservation path (no lost updates
 * on {@code reserved}/{@code status}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogSeedingServiceImpl implements CatalogSeedingService {

    private final FlightInventoryRepository flightInventoryRepository;
    private final RoomTypeNightAvailabilityRepository roomTypeAvailabilityRepository;
    private final ConsumedEventRepository consumedEventRepository;
    private final HotelCalendarProperties properties;
    private final Clock clock;

    // -------------------------------------------------------------------------
    // Flight (scalar)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void upsertFlight(UUID eventId, ConsumerEventType eventType, UUID flightId, int totalSeats) {
        if (alreadyConsumed(eventId, eventType, "flightId", flightId)) {
            return;
        }
        flightInventoryRepository
                .findForUpdate(flightId)
                .ifPresentOrElse(
                        inventory -> {
                            inventory.updateCapacity(totalSeats);
                            warnIfFlightOversold(inventory);
                        },
                        () -> flightInventoryRepository.save(new FlightInventory(
                                UUID.randomUUID(), flightId, totalSeats, 0, InventoryStatus.ACTIVE)));
        consumedEventRepository.save(new ConsumedEvent(eventId, eventType));
        log.info("Seeded flight inventory ({}): flightId={}, totalSeats={}", eventType, flightId, totalSeats);
    }

    @Override
    @Transactional
    public void disableFlight(UUID eventId, UUID flightId) {
        if (alreadyConsumed(eventId, ConsumerEventType.FLIGHT_DELETED, "flightId", flightId)) {
            return;
        }
        flightInventoryRepository
                .findForUpdate(flightId)
                .ifPresentOrElse(
                        FlightInventory::disable,
                        () -> log.warn("FlightDeleted for unknown flight, no-op: flightId={}", flightId));
        consumedEventRepository.save(new ConsumedEvent(eventId, ConsumerEventType.FLIGHT_DELETED));
        log.info("Disabled flight inventory: flightId={}", flightId);
    }

    // -------------------------------------------------------------------------
    // Hotel (per-night calendar)
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void upsertHotel(UUID eventId, ConsumerEventType eventType, UUID hotelId, List<RoomTypeSeed> roomTypes) {
        if (alreadyConsumed(eventId, eventType, "hotelId", hotelId)) {
            return;
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate endExclusive = today.plusDays(properties.horizonDays());

        // Lock the hotel's future calendar once (deterministic order), then update/insert/reconcile.
        List<RoomTypeNightAvailability> nightAvailabilities =
                roomTypeAvailabilityRepository.findForUpdateByHotelIdFromDate(hotelId, today);

        Map<UUID, List<RoomTypeNightAvailability>> groupedRoomTypeAvailability =
                nightAvailabilities.stream().collect(Collectors.groupingBy(RoomTypeNightAvailability::getRoomTypeId));

        // Materialize / update every published room type across the horizon.
        roomTypes.forEach(roomType -> {
            List<RoomTypeNightAvailability> existingAvailabilities =
                    groupedRoomTypeAvailability.getOrDefault(roomType.roomTypeId(), List.of());
            Set<LocalDate> existingDates = new HashSet<>();
            existingAvailabilities.forEach(existingNightAvailability -> {
                existingDates.add(existingNightAvailability.getStayDate());
                updateRoomTypeAvailabilityCapacity(existingNightAvailability, roomType, endExclusive);
            });

            for (LocalDate date = today; date.isBefore(endExclusive); date = date.plusDays(1)) {
                if (!existingDates.contains(date)) {
                    roomTypeAvailabilityRepository.save(new RoomTypeNightAvailability(
                            UUID.randomUUID(),
                            roomType.roomTypeId(),
                            hotelId,
                            date,
                            roomType.totalRooms(),
                            0,
                            InventoryStatus.ACTIVE));
                }
            }
        });

        // Reconcile removals: room types no longer published are withdrawn on their future nights.
        Set<UUID> present = roomTypes.stream().map(RoomTypeSeed::roomTypeId).collect(Collectors.toSet());
        for (RoomTypeNightAvailability row : nightAvailabilities) {
            if (row.isActive() && !present.contains(row.getRoomTypeId())) {
                row.disable();
            }
        }

        consumedEventRepository.save(new ConsumedEvent(eventId, eventType));
        log.info(
                "Seeded hotel calendar ({}): hotelId={}, roomTypes={}, horizonDays={}",
                eventType,
                hotelId,
                roomTypes.size(),
                properties.horizonDays());
    }

    @Override
    @Transactional
    public void disableHotel(UUID eventId, UUID hotelId) {
        if (alreadyConsumed(eventId, ConsumerEventType.HOTEL_DELETED, "hotelId", hotelId)) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        List<RoomTypeNightAvailability> futureRows =
                roomTypeAvailabilityRepository.findForUpdateByHotelIdFromDate(hotelId, today);
        if (futureRows.isEmpty()) {
            log.warn("HotelDeleted for unknown hotel, no-op: hotelId={}", hotelId);
        }
        for (RoomTypeNightAvailability row : futureRows) {
            if (row.isActive()) {
                row.disable();
            }
        }
        consumedEventRepository.save(new ConsumedEvent(eventId, ConsumerEventType.HOTEL_DELETED));
        log.info("Disabled hotel calendar (future nights): hotelId={}, nights={}", hotelId, futureRows.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Catalog validation prevents this; if observed, never go negative — clamp (available()) and log
     * ERROR.
     */
    private void warnIfFlightOversold(FlightInventory inventory) {
        if (inventory.isOversold()) {
            log.error(
                    "Published flight capacity below reserved (oversold) — clamping available to 0: "
                            + "flightId={}, totalCapacity={}, reservedCount={}",
                    inventory.getResourceId(),
                    inventory.getTotalCapacity(),
                    inventory.getReservedCount());
        }
    }

    private void updateRoomTypeAvailabilityCapacity(
            RoomTypeNightAvailability roomTypeNightAvailability, RoomTypeSeed roomTypeSeed, LocalDate endExclusive) {
        if (roomTypeNightAvailability.getStayDate().isBefore(endExclusive)) {
            roomTypeNightAvailability.updateCapacity(roomTypeSeed.totalRooms());
            warnIfHotelOversold(roomTypeNightAvailability);
        }
    }

    private void warnIfHotelOversold(RoomTypeNightAvailability row) {
        if (row.isOversold()) {
            log.error(
                    "Published room capacity below reserved (oversold) — clamping available to 0: "
                            + "roomTypeId={}, stayDate={}, totalRooms={}, reserved={}",
                    row.getRoomTypeId(),
                    row.getStayDate(),
                    row.getTotalRooms(),
                    row.getReserved());
        }
    }

    private boolean alreadyConsumed(UUID eventId, ConsumerEventType eventType, String idField, UUID idValue) {
        if (consumedEventRepository.existsById(eventId)) {
            log.info("Skipping duplicate {}: eventId={}, {}={}", eventType, eventId, idField, idValue);
            return true;
        }
        return false;
    }
}
