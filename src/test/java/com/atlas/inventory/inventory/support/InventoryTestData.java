package com.atlas.inventory.inventory.support;

import com.atlas.inventory.entity.FlightInventory;
import com.atlas.inventory.entity.FlightReservation;
import com.atlas.inventory.entity.HotelReservation;
import com.atlas.inventory.entity.InventoryStatus;
import com.atlas.inventory.entity.ReservationStatus;
import com.atlas.inventory.entity.RoomTypeNightAvailability;
import com.atlas.inventory.service.RequestedItem;
import com.atlas.inventory.service.ReserveCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Shared fixtures for Inventory unit tests. */
public final class InventoryTestData {

    public static final UUID BOOKING_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    public static final UUID FLIGHT_RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    public static final UUID HOTEL_RESOURCE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    public static final UUID RESERVATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    public static final String CORRELATION_ID = "test-correlation-id";
    public static final String SAGA_ID = "00000000-0000-0000-0000-000000000099";
    public static final BigDecimal ITEM_AMOUNT = new BigDecimal("100.00");
    public static final BigDecimal TOTAL = new BigDecimal("100.00");

    // Catalog seeding fixtures
    public static final UUID FLIGHT_ID = FLIGHT_RESOURCE_ID;
    public static final UUID HOTEL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    public static final UUID ROOM_TYPE_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID ROOM_TYPE_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    public static final Instant NOW = Instant.parse("2026-06-20T12:00:00Z");
    public static final LocalDate TODAY = LocalDate.of(2026, 6, 20);
    // A 2-night stay well inside the horizon: nights 2026-08-01, 2026-08-02.
    public static final LocalDate CHECK_IN = LocalDate.of(2026, 8, 1);
    public static final LocalDate CHECK_OUT = LocalDate.of(2026, 8, 3);

    private InventoryTestData() {}

    // ── Flight inventory ─────────────────────────────────────────────────────

    public static FlightInventory aFlight(int totalCapacity, int reservedCount, InventoryStatus status) {
        return new FlightInventory(UUID.randomUUID(), FLIGHT_RESOURCE_ID, totalCapacity, reservedCount, status);
    }

    public static FlightInventory anActiveFlight(int totalCapacity, int reservedCount) {
        return aFlight(totalCapacity, reservedCount, InventoryStatus.ACTIVE);
    }

    // ── Room-type night availability ─────────────────────────────────────────

    public static RoomTypeNightAvailability aNight(
            LocalDate stayDate, int totalRooms, int reserved, InventoryStatus status) {
        return new RoomTypeNightAvailability(
                UUID.randomUUID(), HOTEL_RESOURCE_ID, HOTEL_ID, stayDate, totalRooms, reserved, status);
    }

    /** The night rows for the standard {@link #CHECK_IN}..{@link #CHECK_OUT} stay, all with the same capacity. */
    public static List<RoomTypeNightAvailability> stayNights(int totalRooms, int reserved, InventoryStatus status) {
        return List.of(
                aNight(CHECK_IN, totalRooms, reserved, status),
                aNight(CHECK_IN.plusDays(1), totalRooms, reserved, status));
    }

    // ── Reservations ─────────────────────────────────────────────────────────

    public static FlightReservation aFlightReservation(ReservationStatus status) {
        return new FlightReservation(
                UUID.randomUUID(),
                BOOKING_ID,
                FLIGHT_RESOURCE_ID,
                1,
                status,
                NOW.plusSeconds(900),
                CORRELATION_ID,
                SAGA_ID);
    }

    public static HotelReservation aHotelReservation(ReservationStatus status, int quantity) {
        return new HotelReservation(
                UUID.randomUUID(),
                BOOKING_ID,
                HOTEL_RESOURCE_ID,
                quantity,
                status,
                NOW.plusSeconds(900),
                CORRELATION_ID,
                SAGA_ID,
                CHECK_IN,
                CHECK_OUT);
    }

    // ── Requested items / commands ───────────────────────────────────────────

    public static RequestedItem aFlightItem(int quantity) {
        return RequestedItem.flight(FLIGHT_RESOURCE_ID, quantity, ITEM_AMOUNT);
    }

    public static RequestedItem aHotelItem(int quantity) {
        return RequestedItem.hotel(HOTEL_RESOURCE_ID, quantity, ITEM_AMOUNT, CHECK_IN, CHECK_OUT);
    }

    public static ReserveCommand aReserveCommand(RequestedItem... items) {
        return new ReserveCommand(BOOKING_ID, CORRELATION_ID, SAGA_ID, List.of(items), TOTAL);
    }
}
