package com.atlas.inventory.service;

import java.util.List;
import java.util.UUID;

/**
 * Keeps Inventory availability in sync with the Flight and Hotel catalogs
 * (features/seed-inventory-from-catalog). Every method is {@code @Transactional} and idempotent on the
 * envelope {@code eventId} (EVT-005, EVT-008); reactions are local upserts only — this feature
 * publishes no events (ARCH-003: capacity flows only through events, never cross-service DB reads).
 */
public interface CatalogSeedingService {

    /** {@code FlightCreated} / {@code FlightUpdated}: upsert one FLIGHT row, capacity = {@code totalSeats}. */
    void upsertFlight(UUID eventId, String eventType, UUID flightId, int totalSeats);

    /** {@code FlightDeleted}: DISABLE the flight's row (no new reservations); no-op if unknown. */
    void disableFlight(UUID eventId, UUID flightId);

    /**
     * {@code HotelCreated} / {@code HotelUpdated}: upsert one HOTEL row per room type (capacity =
     * {@code totalRooms}) and DISABLE rows of this hotel absent from {@code roomTypes} (removed room types).
     */
    void upsertHotel(UUID eventId, String eventType, UUID hotelId, List<RoomTypeSeed> roomTypes);

    /** {@code HotelDeleted}: DISABLE every per-room-type row of the hotel; no-op if unknown. */
    void disableHotel(UUID eventId, UUID hotelId);
}
