package com.atlas.inventory.event;

import com.atlas.inventory.entity.ResourceType;

/**
 * Produced event {@code eventType} names (inventory-events.yaml message names). Stored on each
 * outbox row and used by {@code OutboxRelay} to resolve the destination topic. Booking-facing names
 * are fixed; resource-facing names depend on the {@link ResourceType}.
 */
public final class InventoryEventTypes {

    // ── Booking-facing (saga) ──
    public static final String INVENTORY_RESERVED = "InventoryReserved";
    public static final String INVENTORY_REJECTED = "InventoryRejected";
    public static final String INVENTORY_RELEASED = "InventoryReleased";

    // ── Resource-facing (availability) ──
    public static final String FLIGHT_SEATS_RESERVED      = "FlightSeatsReserved";
    public static final String FLIGHT_SEATS_RELEASED      = "FlightSeatsReleased";
    public static final String FLIGHT_RESERVATION_EXPIRED = "FlightReservationExpired";
    public static final String HOTEL_ROOMS_RESERVED       = "HotelRoomsReserved";
    public static final String HOTEL_ROOMS_RELEASED       = "HotelRoomsReleased";
    public static final String HOTEL_RESERVATION_EXPIRED  = "HotelReservationExpired";

    private InventoryEventTypes() {}

    /** Resource-facing reserved event name for the given resource type. */
    public static String reserved(ResourceType type) {
        return type == ResourceType.FLIGHT ? FLIGHT_SEATS_RESERVED : HOTEL_ROOMS_RESERVED;
    }

    /** Resource-facing released event name for the given resource type. */
    public static String released(ResourceType type) {
        return type == ResourceType.FLIGHT ? FLIGHT_SEATS_RELEASED : HOTEL_ROOMS_RELEASED;
    }

    /** Resource-facing expired event name for the given resource type. */
    public static String expired(ResourceType type) {
        return type == ResourceType.FLIGHT ? FLIGHT_RESERVATION_EXPIRED : HOTEL_RESERVATION_EXPIRED;
    }
}
