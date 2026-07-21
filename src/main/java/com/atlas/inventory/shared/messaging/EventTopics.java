package com.atlas.inventory.shared.messaging;

/**
 * Kafka topic name constants (topics.md, naming: domain.entity.event).
 * Topics prefixed inventory.* are owned by Inventory Service.
 * Topics prefixed booking.* are owned by Booking Service; constants are defined here for
 * consumer reference only. Topic names are immutable — never rename or reuse a topic.
 */
public final class EventTopics {

    // ── Inventory Service produces — booking-facing (saga), keyed by bookingId ──
    public static final String INVENTORY_BOOKING_RESERVED = "inventory.reserved";
    public static final String INVENTORY_BOOKING_REJECTED = "inventory.rejected";
    public static final String INVENTORY_BOOKING_RELEASED = "inventory.released";

    // ── Inventory Service produces — resource-facing (availability), keyed by reservationId ──
    public static final String INVENTORY_FLIGHT_RESERVED = "inventory.flight.reserved";
    public static final String INVENTORY_FLIGHT_RELEASED = "inventory.flight.released";
    public static final String INVENTORY_FLIGHT_EXPIRED = "inventory.flight.expired";
    public static final String INVENTORY_HOTEL_RESERVED = "inventory.hotel.reserved";
    public static final String INVENTORY_HOTEL_RELEASED = "inventory.hotel.released";
    public static final String INVENTORY_HOTEL_EXPIRED = "inventory.hotel.expired";

    // ── Inventory Service consumes (owned by Booking Service) ─────────────────
    public static final String BOOKING_CREATED = "booking.created";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String BOOKING_CANCELLED = "booking.cancelled";
    public static final String BOOKING_FAILED = "booking.failed";
    public static final String BOOKING_EXPIRED = "booking.expired";

    // ── Inventory Service consumes (owned by Flight Service) ──────────────────
    public static final String FLIGHT_CREATED = "flight.created";
    public static final String FLIGHT_UPDATED = "flight.updated";
    public static final String FLIGHT_DELETED = "flight.deleted";

    // ── Inventory Service consumes (owned by Hotel Service) ───────────────────
    public static final String HOTEL_CREATED = "hotel.created";
    public static final String HOTEL_UPDATED = "hotel.updated";
    public static final String HOTEL_DELETED = "hotel.deleted";

    private EventTopics() {}
}
