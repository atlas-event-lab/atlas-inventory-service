package com.atlas.inventory.service;

import com.atlas.inventory.entity.ConsumedEvent;
import com.atlas.inventory.entity.Inventory;
import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.repository.ConsumedEventRepository;
import com.atlas.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maintains {@link Inventory} rows from Flight/Hotel catalog events
 * (features/seed-inventory-from-catalog). Idempotent on the envelope {@code eventId}; publishes nothing.
 * Writes that touch an existing row take the pessimistic lock ({@code findForUpdate}) so seeding
 * serializes with the reservation path (no lost updates on {@code reservedCount}/{@code status}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogSeedingServiceImpl implements CatalogSeedingService {

    private final InventoryRepository inventoryRepository;
    private final ConsumedEventRepository consumedEventRepository;

    // -------------------------------------------------------------------------
    // Flight
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void upsertFlight(UUID eventId, String eventType, UUID flightId, int totalSeats) {
        if (alreadyConsumed(eventId, eventType, "flightId", flightId)) {
            return;
        }
        upsertRow(ResourceType.FLIGHT, flightId, null, totalSeats);
        consumedEventRepository.save(new ConsumedEvent(eventId, eventType));
        log.info("Seeded flight inventory ({}): flightId={}, totalSeats={}", eventType, flightId, totalSeats);
    }

    @Override
    @Transactional
    public void disableFlight(UUID eventId, UUID flightId) {
        if (alreadyConsumed(eventId, "FlightDeleted", "flightId", flightId)) {
            return;
        }
        boolean disabled = lockedDisable(ResourceType.FLIGHT, flightId);
        if (!disabled) {
            log.warn("FlightDeleted for unknown flight, no-op: flightId={}", flightId);
        }
        consumedEventRepository.save(new ConsumedEvent(eventId, "FlightDeleted"));
        log.info("Disabled flight inventory: flightId={}", flightId);
    }

    // -------------------------------------------------------------------------
    // Hotel
    // -------------------------------------------------------------------------

    @Override
    @Transactional
    public void upsertHotel(UUID eventId, String eventType, UUID hotelId, List<RoomTypeSeed> roomTypes) {
        if (alreadyConsumed(eventId, eventType, "hotelId", hotelId)) {
            return;
        }

        for (RoomTypeSeed roomType : roomTypes) {
            upsertRow(ResourceType.HOTEL, roomType.roomTypeId(), hotelId, roomType.totalRooms());
        }

        // Reconcile removals: room types no longer published by the catalog are withdrawn (DISABLED).
        Set<UUID> present = roomTypes.stream().map(RoomTypeSeed::roomTypeId).collect(Collectors.toSet());
        for (Inventory existing : inventoryRepository.findByParentResourceId(hotelId)) {
            if (existing.isActive() && !present.contains(existing.getResourceId())) {
                lockedDisable(ResourceType.HOTEL, existing.getResourceId());
                log.info("Disabled removed room type on HotelUpdated: hotelId={}, roomTypeId={}",
                        hotelId, existing.getResourceId());
            }
        }

        consumedEventRepository.save(new ConsumedEvent(eventId, eventType));
        log.info("Seeded hotel inventory ({}): hotelId={}, roomTypes={}", eventType, hotelId, roomTypes.size());
    }

    @Override
    @Transactional
    public void disableHotel(UUID eventId, UUID hotelId) {
        if (alreadyConsumed(eventId, "HotelDeleted", "hotelId", hotelId)) {
            return;
        }
        List<Inventory> rows = inventoryRepository.findByParentResourceId(hotelId);
        if (rows.isEmpty()) {
            log.warn("HotelDeleted for unknown hotel, no-op: hotelId={}", hotelId);
        }
        for (Inventory row : rows) {
            lockedDisable(ResourceType.HOTEL, row.getResourceId());
        }
        consumedEventRepository.save(new ConsumedEvent(eventId, "HotelDeleted"));
        log.info("Disabled hotel inventory: hotelId={}, rooms={}", hotelId, rows.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates an ACTIVE row, or updates an existing one's capacity to the absolute published value. */
    private void upsertRow(ResourceType resourceType, UUID resourceId, UUID parentResourceId, int capacity) {
        inventoryRepository.findForUpdate(resourceType, resourceId).ifPresentOrElse(
                inventory -> {
                    inventory.updateCapacity(capacity);
                    warnIfOversold(inventory);
                },
                () -> inventoryRepository.save(new Inventory(
                        UUID.randomUUID(), resourceType, resourceId, parentResourceId,
                        capacity, 0, InventoryStatus.ACTIVE)));
    }

    /** Locks and disables a row. Returns false if no such row exists. */
    private boolean lockedDisable(ResourceType resourceType, UUID resourceId) {
        return inventoryRepository.findForUpdate(resourceType, resourceId)
                .map(inventory -> {
                    inventory.disable();
                    return true;
                })
                .orElse(false);
    }

    /** Catalog validation prevents this; if observed, never go negative — clamp (available()) and log ERROR. */
    private void warnIfOversold(Inventory inventory) {
        if (inventory.isOversold()) {
            log.error("Published capacity below reserved (oversold) — clamping available to 0: "
                    + "resourceType={}, resourceId={}, totalCapacity={}, reservedCount={}",
                    inventory.getResourceType(), inventory.getResourceId(),
                    inventory.getTotalCapacity(), inventory.getReservedCount());
        }
    }

    private boolean alreadyConsumed(UUID eventId, String eventType, String idField, UUID idValue) {
        if (consumedEventRepository.existsById(eventId)) {
            log.info("Skipping duplicate {}: eventId={}, {}={}", eventType, eventId, idField, idValue);
            return true;
        }
        return false;
    }
}
