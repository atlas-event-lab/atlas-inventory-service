package com.atlas.inventory.inventory.support;

import com.atlas.inventory.entity.Inventory;
import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.Reservation;
import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.entity.ResourceType;
import com.atlas.inventory.service.RequestedItem;
import com.atlas.inventory.service.ReserveCommand;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared fixtures for Inventory unit tests. */
public final class InventoryTestData {

    public static final UUID BOOKING_ID         = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID EVENT_ID           = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID FLIGHT_RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    public static final UUID HOTEL_RESOURCE_ID  = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    public static final UUID RESERVATION_ID     = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    public static final String CORRELATION_ID   = "test-correlation-id";
    public static final String SAGA_ID          = "00000000-0000-0000-0000-000000000099";

    // Catalog seeding fixtures
    public static final UUID FLIGHT_ID    = FLIGHT_RESOURCE_ID;
    public static final UUID HOTEL_ID     = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    public static final UUID ROOM_TYPE_A  = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID ROOM_TYPE_B  = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    public static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");

    private InventoryTestData() {}

    public static Inventory anInventory(ResourceType type, UUID resourceId,
                                        int totalCapacity, int reservedCount, InventoryStatus status) {
        return new Inventory(UUID.randomUUID(), type, resourceId, totalCapacity, reservedCount, status);
    }

    public static Inventory anActiveFlight(int totalCapacity, int reservedCount) {
        return anInventory(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID, totalCapacity, reservedCount, InventoryStatus.ACTIVE);
    }

    public static Inventory anActiveHotel(int totalCapacity, int reservedCount) {
        return anInventory(ResourceType.HOTEL, HOTEL_RESOURCE_ID, totalCapacity, reservedCount, InventoryStatus.ACTIVE);
    }

    public static Reservation aReservation(ReservationStatus status, ResourceType type,
                                           UUID resourceId, int quantity) {
        return new Reservation(UUID.randomUUID(), BOOKING_ID, type, resourceId, quantity,
                status, NOW.plusSeconds(900), CORRELATION_ID, SAGA_ID);
    }

    public static Reservation aFlightReservation(ReservationStatus status) {
        return aReservation(status, ResourceType.FLIGHT, FLIGHT_RESOURCE_ID, 1);
    }

    public static RequestedItem aFlightItem(int quantity) {
        return new RequestedItem(ResourceType.FLIGHT, FLIGHT_RESOURCE_ID, quantity);
    }

    public static RequestedItem aHotelItem(int quantity) {
        return new RequestedItem(ResourceType.HOTEL, HOTEL_RESOURCE_ID, quantity);
    }

    public static ReserveCommand aReserveCommand(RequestedItem... items) {
        return new ReserveCommand(BOOKING_ID, CORRELATION_ID, SAGA_ID, List.of(items));
    }

    public static Inventory aHotelRoom(UUID roomTypeId, UUID hotelId, int totalCapacity, int reservedCount,
                                       InventoryStatus status) {
        return new Inventory(UUID.randomUUID(), ResourceType.HOTEL, roomTypeId, hotelId,
                totalCapacity, reservedCount, status);
    }

    // ── Catalog event envelopes (Map form, as the consumer receives them) ──

    public static Map<String, Object> aFlightEnvelope(UUID eventId, UUID flightId, int totalSeats) {
        return Map.of(
                "eventId", eventId.toString(),
                "eventType", "FlightCreated",
                "payload", Map.of("flightId", flightId.toString(), "totalSeats", totalSeats));
    }

    public static Map<String, Object> aFlightDeletedEnvelope(UUID eventId, UUID flightId) {
        return Map.of(
                "eventId", eventId.toString(),
                "eventType", "FlightDeleted",
                "payload", Map.of("flightId", flightId.toString()));
    }

    public static Map<String, Object> aHotelEnvelope(UUID eventId, UUID hotelId, Map<UUID, Integer> roomTypes) {
        List<Map<String, Object>> rooms = roomTypes.entrySet().stream()
                .map(e -> Map.<String, Object>of(
                        "roomTypeId", e.getKey().toString(), "totalRooms", e.getValue()))
                .toList();
        return Map.of(
                "eventId", eventId.toString(),
                "eventType", "HotelCreated",
                "payload", Map.of("hotelId", hotelId.toString(), "roomTypes", rooms));
    }

    public static Map<String, Object> aHotelDeletedEnvelope(UUID eventId, UUID hotelId) {
        return Map.of(
                "eventId", eventId.toString(),
                "eventType", "HotelDeleted",
                "payload", Map.of("hotelId", hotelId.toString()));
    }
}
